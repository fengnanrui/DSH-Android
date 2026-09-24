package com.fengnanrui.dshandroid;

import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SessionControllerTest extends InstrumentationTestCase {
    public void testQueuedPromptsKeepTheirSessionAfterUiDetach() throws Exception { exerciseQueue(false, false); }
    public void testCancelRestoresQueuedTextWithoutSendingIt() throws Exception { exerciseQueue(true, false); }
    public void testFailureRestoresQueuedTextWithoutSendingIt() throws Exception { exerciseQueue(false, true); }

    private void exerciseQueue(boolean cancel, boolean fail) throws Exception {
        android.content.Context context = getInstrumentation().getTargetContext();
        AppStore store = new AppStore(context, cancel ? "queue-cancel" : fail ? "queue-failure" : "queue-session");
        AppStore.Session first = store.createSession(), second = store.createSession();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        CountDownLatch restored = new CountDownLatch(1);
        ModelTransport model = new ModelTransport() {
            @Override public ModelClient.Completion complete(ProviderRegistry.Provider p, String base, String id,
                    String key, JSONArray history, JSONArray tools) throws Exception {
                entered.countDown(); release.await();
                if (fail) throw new java.io.IOException("fixture request failed");
                JSONObject message = new JSONObject().put("role", "assistant").put("content", "fixture response");
                return new ModelClient.Completion("fixture response", Collections.emptyList(), message);
            }
            @Override public void cancel() {}
        };
        SessionController controller = new SessionController(store, new SecretStore(context), model);
        SessionController.Listener listener = new SessionController.Listener() {
            @Override public void changed(String session, boolean messages) {
                if ((cancel || fail) && session.equals(second.id) && !controller.pending(second)) {
                    assertEquals("second prompt", controller.drafts.get(second.id));
                    restored.countDown();
                }
                if (!controller.running() && (cancel || fail || !store.messages(second).isEmpty())) finished.countDown();
            }
            @Override public void approval(String tool, String args, AgentRuntime.ApprovalDecision decision) { fail("No approval expected"); }
            @Override public void question(String text, AgentRuntime.UserAnswer answer) { fail("No question expected"); }
        };
        try {
            getInstrumentation().runOnMainSync(() -> {
                controller.attach(listener); controller.submit(first, "first prompt");
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            getInstrumentation().runOnMainSync(() -> {
                controller.detach(); controller.submit(second, "second prompt");
                controller.drafts.put(second.id, "");
                assertTrue(controller.pending(second));
                controller.attach(listener);
                if (cancel) controller.cancel();
            });
            if (!cancel) release.countDown();
            assertTrue("Queue must finish", finished.await(3, TimeUnit.SECONDS));
            assertEquals("first prompt", store.messages(first).get(0).content);
            if (cancel || fail) {
                assertTrue(store.messages(second).isEmpty());
                assertEquals("Cancelled session must publish its restored draft", 0L, restored.getCount());
                getInstrumentation().runOnMainSync(() -> assertEquals("second prompt", controller.drafts.get(second.id)));
            } else {
                assertEquals("second prompt", store.messages(second).get(0).content);
                assertEquals(2, store.messages(first).size());
                assertEquals(2, store.messages(second).size());
            }
        } finally {
            release.countDown();
            getInstrumentation().runOnMainSync(controller::close);
            store.deleteSession(first.id); store.deleteSession(second.id);
        }
    }
}
