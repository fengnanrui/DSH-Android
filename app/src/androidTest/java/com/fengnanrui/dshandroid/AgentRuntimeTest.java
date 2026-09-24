package com.fengnanrui.dshandroid;

import android.test.AndroidTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentRuntimeTest extends AndroidTestCase {
    public void testRevokedSubagentCannotStartAnotherModelRequest() throws Exception {
        exerciseRevokedTool("delegate_task", "tool-subagent");
    }

    public void testRevokedQuestionCannotOpenADialog() throws Exception {
        exerciseRevokedTool("ask_user", "tool-ask-user");
    }

    private void exerciseRevokedTool(String tool, String plugin) throws Exception {
        AppStore store = new AppStore(getContext(), "revoked-" + tool);
        AppStore.Session session = store.createSession();
        PluginCatalog.setEnabled(store.settings(), plugin, true);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger questions = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        ModelTransport model = new ModelTransport() {
            @Override public ModelClient.Completion complete(ProviderRegistry.Provider p, String base, String id,
                    String key, JSONArray history, JSONArray tools) throws Exception {
                if (requests.incrementAndGet() == 1) {
                    // Revoke after request assembly but before the model's tool call is dispatched.
                    PluginCatalog.setEnabled(store.settings(), plugin, false);
                    JSONObject args = new JSONObject().put("task", "fixture").put("question", "fixture");
                    ModelClient.ToolCall call = new ModelClient.ToolCall("revoked-call", tool, args);
                    JSONObject message = new JSONObject().put("role", "assistant").put("content", "")
                            .put("_toolCalls", new JSONArray().put(new JSONObject().put("id", call.id)
                                    .put("name", tool).put("arguments", args)));
                    return new ModelClient.Completion("", Collections.singletonList(call), message);
                }
                return new ModelClient.Completion("done", Collections.emptyList(),
                        new JSONObject().put("role", "assistant").put("content", "done"));
            }
            @Override public void cancel() {}
        };
        AgentRuntime runtime = new AgentRuntime(store, new SecretStore(getContext()), model);
        try {
            runtime.run(session, "fixture", new AgentRuntime.Callback() {
                @Override public void onStatus(String status) {}
                @Override public void onMessage(AppStore.Message message) {}
                @Override public void onApproval(String name, String args, AgentRuntime.ApprovalDecision decision) { decision.resolve(false); }
                @Override public void onQuestion(String text, AgentRuntime.UserAnswer answer) { questions.incrementAndGet(); answer.resolve("fixture"); }
                @Override public void onFinished() { finished.countDown(); }
                @Override public void onError(String message) { error.set(message); }
            });
            assertTrue("Run must settle", finished.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals("Revoked tool must not make an extra model request", 2, requests.get());
            assertEquals("Revoked tool must not ask a question", 0, questions.get());
            assertTrue(store.messages(session).get(2).content.contains("未提供工具"));
        } finally {
            runtime.close(); store.jobs().close(); store.deleteSession(session.id);
            PluginCatalog.setEnabled(store.settings(), plugin, true);
        }
    }

    public void testStopUnblocksApprovalAndAllowsNextTurn() throws Exception {
        AppStore store = new AppStore(getContext(), "runtime-cancel-test");
        AppStore.Session session = store.createSession();
        store.settings().edit().putString("permission_mode", "ask").commit();
        ModelTransport model = new ModelTransport() {
            @Override public ModelClient.Completion complete(ProviderRegistry.Provider p, String base, String id,
                    String key, JSONArray history, JSONArray tools) throws Exception {
                JSONObject args = new JSONObject().put("path", "must-not-exist.txt").put("content", "blocked");
                ModelClient.ToolCall call = new ModelClient.ToolCall("pending-write", "write_file", args);
                JSONObject message = new JSONObject().put("role", "assistant").put("content", "")
                        .put("_toolCalls", new JSONArray().put(new JSONObject().put("id", call.id)
                                .put("name", call.name).put("arguments", args)));
                return new ModelClient.Completion("", Collections.singletonList(call), message);
            }
            @Override public void cancel() {}
        };
        AgentRuntime runtime = new AgentRuntime(store, new SecretStore(getContext()), model);
        try {
            for (int run = 0; run < 2; run++) {
                CountDownLatch approval = new CountDownLatch(1), finished = new CountDownLatch(1);
                AtomicReference<String> error = new AtomicReference<>();
                runtime.run(session, "test cancel", new AgentRuntime.Callback() {
                    @Override public void onStatus(String status) {}
                    @Override public void onMessage(AppStore.Message message) {}
                    @Override public void onApproval(String tool, String args, AgentRuntime.ApprovalDecision decision) { approval.countDown(); }
                    @Override public void onQuestion(String question, AgentRuntime.UserAnswer answer) { answer.resolve(""); }
                    @Override public void onFinished() { finished.countDown(); }
                    @Override public void onError(String message) { error.set(message); }
                });
                assertTrue("Approval should be reached", approval.await(5, TimeUnit.SECONDS));
                runtime.cancel();
                assertTrue("Stop must release an unanswered approval", finished.await(2, TimeUnit.SECONDS));
                assertNull(error.get());
            }
            assertFalse(new File(store.workspace(), "must-not-exist.txt").exists());
            JSONArray replay = ConversationHistory.replay(store.messages(session));
            assertEquals("tool", replay.getJSONObject(replay.length() - 1).getString("role"));
        } finally {
            runtime.close(); store.jobs().close(); store.deleteSession(session.id);
        }
    }

    public void testExecutorEnforcesPresetAndDisabledPlugin() throws Exception {
        AppStore store = new AppStore(getContext(), "runtime-plugin-test");
        AppStore.Session session = store.createSession();
        try {
            session.presetId = "minimal";
            MobileTools tools = new MobileTools(store.workspace(), store, session);
            try { tools.execute("current_time", new JSONObject()); fail("Preset must deny undeclared tools"); }
            catch (SecurityException expected) { assertTrue(expected.getMessage().contains("未提供")); }
            session.presetId = "standard";
            PluginCatalog.setEnabled(store.settings(), "timer", false);
            try { tools.execute("current_time", new JSONObject()); fail("Disabled plugin must deny direct execution"); }
            catch (SecurityException expected) { assertTrue(expected.getMessage().contains("未提供")); }
        } finally {
            PluginCatalog.setEnabled(store.settings(), "timer", true);
            store.deleteSession(session.id); store.jobs().close();
        }
    }
}
