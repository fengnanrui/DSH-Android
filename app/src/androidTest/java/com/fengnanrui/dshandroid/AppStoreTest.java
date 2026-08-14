package com.fengnanrui.dshandroid;

import android.test.AndroidTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

public final class AppStoreTest extends AndroidTestCase {
    private static final String NAMESPACE = "instrumentation";

    @Override protected void setUp() throws Exception {
        super.setUp();
        cleanup();
    }

    @Override protected void tearDown() throws Exception {
        cleanup();
        super.tearDown();
    }

    public void testToolProtocolContextSurvivesReload() {
        AppStore first = new AppStore(getContext(), NAMESPACE);
        AppStore.Session session = first.sessions().get(0);
        String canonical = "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call-1\"}]}";
        first.addMessage(session, new AppStore.Message("assistant", "", "", "", canonical, 1L));
        first.addMessage(session, new AppStore.Message("tool", "done", "read_file", "call-1", "", 2L));
        first.jobs().close();

        AppStore second = new AppStore(getContext(), NAMESPACE);
        AppStore.Session reloaded = second.sessions().get(0);
        assertEquals(canonical, reloaded.messages.get(0).canonicalJson);
        assertEquals("call-1", reloaded.messages.get(1).toolCallId);
        second.jobs().close();
    }

    public void testCorruptMainFileRecoversBackupAndWarns() throws Exception {
        AppStore first = new AppStore(getContext(), NAMESPACE);
        AppStore.Session session = first.sessions().get(0);
        first.addMessage(session, new AppStore.Message("user", "first"));
        first.addMessage(session, new AppStore.Message("assistant", "second"));
        first.jobs().close();

        File main = new File(getContext().getFilesDir(), "state_" + NAMESPACE + "/sessions.json");
        try (FileOutputStream output = new FileOutputStream(main, false)) {
            output.write("not-json".getBytes(StandardCharsets.UTF_8));
        }

        AppStore recovered = new AppStore(getContext(), NAMESPACE);
        assertFalse(recovered.persistenceWarning().trim().isEmpty());
        assertEquals("first", recovered.sessions().get(0).messages.get(0).content);
        recovered.jobs().close();
    }

    public void testJobsRespectConcurrencyLimitAndCanStop() throws Exception {
        AppStore store = new AppStore(getContext(), NAMESPACE);
        store.savePluginConfig(20, 64, 3, 5, 30, 1);
        MobileTools tools = new MobileTools(store.workspace(), store, store.sessions().get(0));
        assertTrue(tools.execute("start_job", new JSONObject().put("command", "sleep 5")).contains("已启动"));
        try {
            tools.execute("start_job", new JSONObject().put("command", "sleep 5"));
            fail("Second concurrent job must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("并发上限"));
        }
        assertTrue(tools.execute("stop_job", new JSONObject().put("id", 1)).contains("已停止"));
        store.jobs().close();
    }

    public void testCodeModeExecutesNativeSteps() throws Exception {
        AppStore store = new AppStore(getContext(), NAMESPACE);
        AppStore.Session session = store.sessions().get(0);
        session.presetId = "code";
        MobileTools tools = new MobileTools(store.workspace(), store, session);
        JSONArray steps = new JSONArray()
                .put(new JSONObject().put("tool", "write_file").put("args",
                        new JSONObject().put("path", "ptc.txt").put("content", "native-code-mode")))
                .put(new JSONObject().put("tool", "read_file").put("args",
                        new JSONObject().put("path", "ptc.txt")));
        String result = tools.execute("run_code_mode", new JSONObject().put("steps", steps.toString()));
        assertTrue(result.contains("native-code-mode"));
        store.jobs().close();
    }

    private void cleanup() {
        deleteRecursively(new File(getContext().getFilesDir(), "state_" + NAMESPACE));
        deleteRecursively(new File(getContext().getFilesDir(), "workspaces/default_" + NAMESPACE));
        getContext().getSharedPreferences("dsh_settings_" + NAMESPACE, 0).edit().clear().commit();
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
