package com.fengnanrui.dshandroid;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public final class ConversationHistoryTest {
    private AppStore.Message assistant() throws Exception {
        JSONObject canonical = new JSONObject().put("role", "assistant").put("content", "")
                .put("_toolCalls", new JSONArray()
                        .put(new JSONObject().put("id", "a").put("name", "read_file").put("arguments", new JSONObject()))
                        .put(new JSONObject().put("id", "b").put("name", "write_file").put("arguments", new JSONObject())));
        return new AppStore.Message("assistant", "", "", "", canonical.toString(), 1);
    }

    @Test public void interruptedBatchIsCompletedBeforeNextUserTurn() throws Exception {
        JSONArray replay = ConversationHistory.replay(Arrays.asList(assistant(),
                new AppStore.Message("tool", "read result", "read_file", "a", "", 2),
                new AppStore.Message("user", "continue")));
        assertEquals(4, replay.length());
        assertEquals("read result", replay.getJSONObject(1).getString("content"));
        assertEquals("b", replay.getJSONObject(2).getString("toolCallId"));
        assertTrue(replay.getJSONObject(2).getString("content").contains("中断"));
        assertEquals("user", replay.getJSONObject(3).getString("role"));
    }

    @Test public void duplicateAndOrphanResultsDoNotBreakProviderProtocol() throws Exception {
        AppStore.Message a = new AppStore.Message("tool", "result", "read_file", "a", "", 2);
        JSONArray replay = ConversationHistory.replay(Arrays.asList(
                new AppStore.Message("tool", "legacy orphan", "read_file", "old", "", 0), assistant(), a, a));
        assertEquals(3, replay.length());
        assertEquals("a", replay.getJSONObject(1).getString("toolCallId"));
        assertEquals("b", replay.getJSONObject(2).getString("toolCallId"));
    }
}
