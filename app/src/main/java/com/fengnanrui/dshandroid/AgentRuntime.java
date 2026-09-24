package com.fengnanrui.dshandroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** On-device agent loop with explicit approval gates for side effects. */
public final class AgentRuntime {
    public interface Callback {
        void onStatus(String status);
        void onMessage(AppStore.Message message);
        void onApproval(String tool, String arguments, ApprovalDecision decision);
        void onQuestion(String question, UserAnswer answer);
        void onFinished();
        void onError(String message);
    }

    public static final class ApprovalDecision {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean approved;
        public void resolve(boolean value) { approved = value; latch.countDown(); }
        boolean await() throws InterruptedException { latch.await(); return approved; }
    }

    public static final class UserAnswer {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String value = "用户取消了回答。";
        public void resolve(String answer) { value = answer == null || answer.trim().isEmpty() ? "用户取消了回答。" : answer; latch.countDown(); }
        String await() throws InterruptedException { latch.await(); return value; }
    }

    private final AppStore store;
    private final SecretStore secrets;
    private final ModelTransport modelClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;
    private volatile MobileTools activeTools;
    private boolean closed;

    public AgentRuntime(AppStore store, SecretStore secrets) {
        this(store, secrets, new ModelClient());
    }

    AgentRuntime(AppStore store, SecretStore secrets, ModelTransport modelClient) {
        this.store = store;
        this.secrets = secrets;
        this.modelClient = modelClient;
    }

    public synchronized void run(AppStore.Session session, String prompt, Callback callback) {
        if (closed) { callback.onError("运行时已关闭"); callback.onFinished(); return; }
        if (!running.compareAndSet(false, true)) {
            callback.onError("已有任务运行，请等待完成或停止后再发送。");
            return;
        }
        cancelled.set(false);
        try {
            store.addMessage(session, new AppStore.Message("user", prompt));
            callback.onMessage(session.messages.get(session.messages.size() - 1));
        } catch (Exception error) {
            running.set(false);
            callback.onError(safeMessage(error));
            callback.onFinished();
            return;
        }
        executor.execute(() -> execute(session, callback));
    }

    public synchronized void cancel() {
        cancelled.set(true);
        Thread current = worker;
        if (current != null) current.interrupt();
        modelClient.cancel();
        MobileTools tools = activeTools;
        if (tools != null) tools.cancel();
    }
    public synchronized void close() { closed = true; cancel(); executor.shutdownNow(); }

    private void execute(AppStore.Session session, Callback callback) {
        try {
            synchronized (this) {
                worker = Thread.currentThread();
                if (cancelled.get()) throw new InterruptedException();
            }
            requirePlugin("agent");
            requirePlugin("agent-loop");
            requirePlugin("llm");
            AppStore.ProviderProfile profile = store.activeProviderProfile();
            ProviderRegistry.Provider provider = profile.runtimeProvider();
            String key = secrets.get(profile.id());
            JSONArray conversation = conversation(session);
            JSONArray definitions = MobileTools.definitions(store, session);
            MobileTools tools = new MobileTools(store.workspace(), store, session);
            activeTools = tools;
            boolean completed = false;
            for (int turn = 0; turn < 8 && !cancelled.get(); turn++) {
                callback.onStatus(turn == 0 ? "正在请求 " + provider.name() : "Agent 正在继续处理…");
                ModelClient.Completion completion = modelClient.complete(provider, profile.baseUrl(), profile.model(),
                        key, conversation, definitions);
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                conversation.put(completion.canonicalMessage);
                if (!completion.content.trim().isEmpty() || !completion.toolCalls.isEmpty()) {
                    AppStore.Message message = new AppStore.Message("assistant", completion.content);
                    message.canonicalJson = completion.canonicalMessage.toString();
                    store.addMessage(session, message);
                    if (!completion.content.trim().isEmpty()) callback.onMessage(message);
                }
                if (completion.toolCalls.isEmpty()) { completed = true; break; }
                int allowedCalls = store.maxToolCallsPerTurn();
                for (int callIndex = 0; callIndex < completion.toolCalls.size(); callIndex++) {
                    ModelClient.ToolCall call = completion.toolCalls.get(callIndex);
                    if (cancelled.get()) break;
                    callback.onStatus("工具：" + call.name);
                    String result;
                    if (!MobileTools.isToolAvailable(definitions, call.name)) {
                        result = "当前会话未开放该工具，未执行。";
                    } else if (callIndex >= allowedCalls) {
                        result = "本轮工具调用超过设置上限（" + allowedCalls + "），未执行。";
                    } else {
                        boolean approved = approveIfNeeded(call, callback);
                        if (!approved) {
                            result = "用户拒绝了此工具调用。";
                        } else {
                            try {
                                tools.requireAvailable(call.name);
                                if ("delegate_task".equals(call.name)) result = delegate(profile, key, call.arguments);
                                else if ("ask_user".equals(call.name)) result = askUser(call.arguments, callback);
                                else result = tools.execute(call.name, call.arguments);
                            }
                            catch (Exception error) { result = "工具执行失败: " + safeMessage(error); }
                        }
                    }
                    JSONObject toolMessage = new JSONObject().put("role", "tool").put("toolCallId", call.id)
                            .put("toolName", call.name).put("content", result);
                    conversation.put(toolMessage);
                    AppStore.Message stored = new AppStore.Message("tool", result, call.name, call.id,
                            "", System.currentTimeMillis());
                    store.addMessage(session, stored);
                    callback.onMessage(stored);
                }
            }
            if (cancelled.get()) callback.onStatus("已停止");
            else callback.onStatus(completed ? "已完成" : "达到本轮执行上限，可发送消息继续");
        } catch (Exception error) {
            if (cancelled.get()) callback.onStatus("已停止");
            else callback.onError(safeMessage(error));
        } finally {
            synchronized (this) { worker = null; activeTools = null; running.set(false); }
            Thread.interrupted();
            callback.onFinished();
        }
    }

    private void requirePlugin(String id) {
        if (!PluginCatalog.enabled(store.settings(), id))
            throw new IllegalStateException("运行所需插件已停用：" + id + "。请在设置 → 插件中重新启用。");
    }

    private boolean approveIfNeeded(ModelClient.ToolCall call, Callback callback) throws InterruptedException {
        if (!MobileTools.requiresApproval(call.name)) return true;
        String mode = store.permissionMode();
        if ("full".equals(mode)) return true;
        if ("workspace".equals(mode) && ("write_file".equals(call.name)
                || "str_replace_editor".equals(call.name))) return true;
        ApprovalDecision decision = new ApprovalDecision();
        callback.onApproval(call.name, call.arguments.toString(), decision);
        return decision.await();
    }

    private String delegate(AppStore.ProviderProfile profile, String key, JSONObject arguments) throws Exception {
        JSONArray conversation = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content",
                        "你是由主 Agent 启动的手机端子 Agent。只分析指定子任务，返回可核验、简洁的结果；不要假装使用工具。"))
                .put(new JSONObject().put("role", "user").put("content",
                        arguments.optString("task") + "\n\n背景：" + arguments.optString("context")));
        ModelClient.Completion completion = modelClient.complete(profile.runtimeProvider(), profile.baseUrl(), profile.model(),
                key, conversation, new JSONArray());
        return completion.content.trim().isEmpty() ? "子 Agent 未返回文本" : completion.content;
    }

    private String askUser(JSONObject arguments, Callback callback) throws InterruptedException {
        UserAnswer answer = new UserAnswer(); callback.onQuestion(arguments.optString("question", "请补充信息"), answer);
        return answer.await();
    }

    private JSONArray conversation(AppStore.Session session) throws Exception {
        JSONArray result = new JSONArray();
        result.put(new JSONObject().put("role", "system").put("content",
                "你是 DSH Android 的本机 Agent。你运行在 Android 应用沙箱中，不依赖电脑或远端 Harness。"
                        + "使用工具检查和修改当前工作区；涉及副作用的调用会由用户审批。"
                        + "当前 Agent 预设是“" + store.preset(session.presetId).name() + "”："
                        + store.preset(session.presetId).description()
                        + "复杂任务先用 update_plan 建立步骤，输出适合手机阅读的简洁中文。"
                        + "不要声称执行了未实际调用的工具。当前工作区根目录对你表示为 .。\n"
                        + store.agentInstructions()));
        JSONArray history = ConversationHistory.replay(store.messages(session));
        for (int i = 0; i < history.length(); i++) result.put(history.getJSONObject(i));
        return result;
    }

    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
