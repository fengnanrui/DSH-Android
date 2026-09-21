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
