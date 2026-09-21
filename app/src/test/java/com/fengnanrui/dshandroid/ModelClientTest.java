package com.fengnanrui.dshandroid;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ModelClientTest {
    @Test public void redactsCredentialsBeforeTruncatingErrorOutput() {
        String key = "fixture-secret-token";
        String result = ModelClient.errorDetail("x".repeat(995) + key,
                new String[][]{{"Authorization", "Bearer " + key}});
        assertEquals(1000, result.length());
        assertFalse(result.contains("fixtu"));
        assertEquals("[redacted] [redacted]", ModelClient.errorDetail("Bearer " + key + " " + key,
                new String[][]{{"Authorization", "Bearer " + key}}));
    }

    @Test public void joinsCustomVersionedEndpointsWithoutDuplicatingTheirVersion() {
        assertEquals("https://example.com/v1/messages", ModelClient.join("https://example.com/v1/", "v1/messages"));
        assertEquals("https://example.com/proxy/v1beta/models/test", ModelClient.join("https://example.com/proxy/v1beta", "v1beta/models/test"));
        assertEquals("https://example.com/v1/chat/completions", ModelClient.join("https://example.com/v1", "chat/completions"));
        assertEquals("https://example.com/v1/messages", ModelClient.join("https://example.com///", "/v1/messages"));
    }

    @Test public void groupsParallelToolResultsForAnthropicAndGemini() throws Exception {
        JSONArray source = new JSONArray().put(new JSONObject().put("role", "assistant").put("content", ""))
                .put(ConversationHistory.toolResult("a", "read_file", "A"))
                .put(ConversationHistory.toolResult("b", "read_file", "B"));
        JSONArray anthropic = ModelClient.anthropicMessages(source);
        assertEquals(2, anthropic.length());
        assertEquals(2, anthropic.getJSONObject(1).getJSONArray("content").length());
        JSONArray gemini = ModelClient.geminiMessages(source);
        assertEquals(2, gemini.length());
        assertEquals(2, gemini.getJSONObject(1).getJSONArray("parts").length());
    }

    @Test public void geminiSignatureSurvivesCanonicalPersistenceAndReplay() throws Exception {
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("thought", true).put("text", "private reasoning"))
                .put(new JSONObject().put("thoughtSignature", "opaque-fixture-signature")
                        .put("functionCall", new JSONObject().put("name", "current_time").put("args", new JSONObject())));
        JSONObject response = new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("content", new JSONObject().put("parts", parts))));
        ModelClient.Completion completion = ModelClient.geminiCompletion(response);
        assertEquals("", completion.content);
        JSONObject persisted = new JSONObject(completion.canonicalMessage.toString());
        JSONArray request = ModelClient.geminiMessages(new JSONArray().put(persisted));
        assertEquals(parts.toString(), request.getJSONObject(0).getJSONArray("parts").toString());
    }

    @Test public void preservesOpenAiReasoningAndRejectsMalformedArguments() throws Exception {
        JSONObject assistant = new JSONObject().put("role", "assistant").put("content", "done")
                .put("reasoning_content", "reasoning fixture");
        assertEquals("reasoning fixture", ModelClient.openAiMessages(new JSONArray().put(assistant))
                .getJSONObject(0).getString("reasoning_content"));
        try { ModelClient.parseArguments("{broken"); fail("Malformed args must not become an empty command"); }
        catch (org.json.JSONException expected) { assertNotNull(expected.getMessage()); }
    }
}
