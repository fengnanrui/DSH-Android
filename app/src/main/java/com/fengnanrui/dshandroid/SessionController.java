package com.fengnanrui.dshandroid;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Owns a foreground runtime across Activity recreation; UI callbacks never own the run. */
final class SessionController implements AutoCloseable {
    interface Listener {
        void changed(String sessionId, boolean messagesChanged);
        void approval(String tool, String arguments, AgentRuntime.ApprovalDecision decision);
        void question(String question, AgentRuntime.UserAnswer answer);
    }

    private record Prompt(AppStore.Session session, String text) {}
    final AppStore store;
    final SecretStore secrets;
    private final AgentRuntime runtime;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Prompt> queue = new ArrayDeque<>();
    private final Map<String, String> statuses = new HashMap<>();
    final Map<String, String> drafts = new HashMap<>();
    private Listener listener;
    private AppStore.Session runningSession;
    private AgentRuntime.ApprovalDecision decision;
    private AgentRuntime.UserAnswer answer;
    private String pendingTool, pendingArguments, pendingQuestion;
    private boolean closed;
    private boolean stopping;

    SessionController(AppStore store, SecretStore secrets) {
        this(store, secrets, new ModelClient());
    }

    SessionController(AppStore store, SecretStore secrets, ModelTransport transport) {
        this.store = store; this.secrets = secrets;
        runtime = new AgentRuntime(store, secrets, transport);
    }

    void attach(Listener listener) {
        this.listener = listener;
        if (decision != null) listener.approval(pendingTool, pendingArguments, decision);
        if (answer != null) listener.question(pendingQuestion, answer);
    }

    void detach() { listener = null; }
    boolean running() { return runningSession != null; }
    boolean running(AppStore.Session session) { return runningSession == session; }
    boolean pending(AppStore.Session session) {
        if (running(session)) return true;
        for (Prompt prompt : queue) if (prompt.session() == session) return true;
        return false;
    }
    String status(AppStore.Session session, String fallback) { return statuses.getOrDefault(session.id, fallback); }

    void submit(AppStore.Session session, String prompt) {
        if (closed) return;
        if (running()) {
            queue.addLast(new Prompt(session, prompt));
            statuses.put(session.id, "消息已排队，等待当前任务结束");
            notifyChanged(session, false);
            return;
        }
        runningSession = session;
        stopping = false;
        statuses.put(session.id, "正在开始…");
        notifyChanged(session, false);
        runtime.run(session, prompt, new AgentRuntime.Callback() {
            @Override public void onStatus(String status) { dispatch(() -> {
                statuses.put(session.id, status); notifyChanged(session, false);
            }); }
            @Override public void onMessage(AppStore.Message message) {
                dispatch(() -> notifyChanged(session, true));
            }
            @Override public void onApproval(String tool, String arguments, AgentRuntime.ApprovalDecision value) {
                dispatch(() -> {
                    if (stopping) { value.resolve(false); return; }
                    decision = value; pendingTool = tool; pendingArguments = arguments;
                    if (listener != null) listener.approval(tool, arguments, value);
                });
            }
            @Override public void onQuestion(String question, AgentRuntime.UserAnswer value) {
                dispatch(() -> {
                    if (stopping) { value.resolve(""); return; }
                    answer = value; pendingQuestion = question;
                    if (listener != null) listener.question(question, value);
                });
            }
            @Override public void onError(String error) { dispatch(() -> {
                statuses.put(session.id, "出错：" + error); clearQueue(); notifyChanged(session, false);
            }); }
            @Override public void onFinished() { dispatch(() -> {
                decision = null; answer = null; runningSession = null;
                notifyChanged(session, false);
                if (!queue.isEmpty()) {
                    Prompt next = queue.removeFirst();
                    submit(next.session(), next.text());
                }
            }); }
        });
    }

    void resolve(AgentRuntime.ApprovalDecision value, boolean allowed) {
        if (decision != value) return;
        decision = null; value.resolve(allowed);
    }

    void resolve(AgentRuntime.UserAnswer value, String text) {
        if (answer != value) return;
        answer = null; value.resolve(text);
    }

    void cancel() {
        stopping = true;
        clearQueue(); decision = null; answer = null;
        runtime.cancel();
        if (runningSession != null) {
            statuses.put(runningSession.id, "正在停止…"); notifyChanged(runningSession, false);
        }
    }

    private void clearQueue() {
        LinkedHashSet<AppStore.Session> restored = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            Prompt prompt = queue.removeFirst();
            drafts.merge(prompt.session().id, prompt.text(), (a, b) -> a.isEmpty() ? b : a + "\n\n" + b);
            statuses.put(prompt.session().id, "排队已取消，未发送的内容保留为草稿");
            restored.add(prompt.session());
        }
        // Publish only after every pending item is removed and its draft is complete.
        for (AppStore.Session session : restored) notifyChanged(session, false);
    }

    private void dispatch(Runnable action) { main.post(() -> { if (!closed) action.run(); }); }
    private void notifyChanged(AppStore.Session session, boolean messages) {
        if (listener != null) listener.changed(session.id, messages);
    }

    @Override public void close() {
        closed = true; detach(); runtime.close(); store.jobs().close();
        main.removeCallbacksAndMessages(null);
    }
}
