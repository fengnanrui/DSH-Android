package com.fengnanrui.dshandroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Replays durable turns, completing interrupted tool batches before the next user turn. */
final class ConversationHistory {
    private ConversationHistory() {}

    static JSONArray replay(List<AppStore.Message> messages) throws Exception {
        JSONArray result = new JSONArray();
        JSONArray pending = new JSONArray();
        Set<String> received = new HashSet<>();
        for (AppStore.Message message : messages) {
            if ("tool".equals(message.role)) {
                if (contains(pending, message.toolCallId) && received.add(message.toolCallId)) {
                    result.put(toolResult(message.toolCallId, message.toolName, message.content));
                }
                continue;
            }
            completeInterrupted(result, pending, received);
            pending = new JSONArray();
            received.clear();
            JSONObject item;
            if ("assistant".equals(message.role) && message.canonicalJson != null
                    && !message.canonicalJson.trim().isEmpty()) {
                item = new JSONObject(message.canonicalJson);
                JSONArray calls = item.optJSONArray("_toolCalls");
                if (calls != null) pending = calls;
            } else {
                item = new JSONObject().put("role", message.role).put("content", message.content);
            }
            result.put(item);
        }
        completeInterrupted(result, pending, received);
        return result;
    }

    private static boolean contains(JSONArray pending, String id) throws Exception {
        if (id == null || id.isEmpty()) return false;
        for (int i = 0; i < pending.length(); i++) {
            if (id.equals(pending.getJSONObject(i).optString("id"))) return true;
        }
        return false;
    }

    private static void completeInterrupted(JSONArray result, JSONArray pending, Set<String> received) throws Exception {
        for (int i = 0; i < pending.length(); i++) {
            JSONObject call = pending.getJSONObject(i);
            String id = call.getString("id");
            if (!received.contains(id)) result.put(toolResult(id, call.optString("name"),
                    "上次运行已中断，该工具没有保存结果。请检查实际状态后再决定是否重试。"));
        }
    }

    static JSONObject toolResult(String id, String name, String content) throws Exception {
        return new JSONObject().put("role", "tool").put("toolCallId", id)
                .put("toolName", name).put("content", content);
    }
}
