package com.fengnanrui.dshandroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Direct model API client; no desktop server or embedded web application is involved. */
public final class ModelClient {
    private volatile HttpURLConnection activeConnection;
    public static final class ToolCall {
        public final String id;
        public final String name;
        public final JSONObject arguments;

        ToolCall(String id, String name, JSONObject arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    public static final class Completion {
        public final String content;
        public final List<ToolCall> toolCalls;
        public final JSONObject canonicalMessage;

        Completion(String content, List<ToolCall> toolCalls, JSONObject canonicalMessage) {
            this.content = content;
            this.toolCalls = toolCalls;
            this.canonicalMessage = canonicalMessage;
        }
    }

    public Completion complete(ProviderRegistry.Provider provider, String baseUrl, String model,
                               String apiKey, JSONArray conversation, JSONArray tools) throws Exception {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("请先在设置中保存 API Key");
        if (!baseUrl.startsWith("https://")) throw new SecurityException("模型地址必须使用 HTTPS");
        return switch (provider.protocol()) {
            case OPENAI -> openAi(baseUrl, model, apiKey, conversation, tools);
            case ANTHROPIC -> anthropic(baseUrl, model, apiKey, conversation, tools);
            case GEMINI -> gemini(baseUrl, model, apiKey, conversation, tools);
        };
    }

    public void cancel() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    private Completion openAi(String base, String model, String key, JSONArray conversation,
                              JSONArray tools) throws Exception {
        JSONObject request = new JSONObject().put("model", model).put("messages", openAiMessages(conversation))
                .put("temperature", 0.2);
        if (tools.length() > 0) request.put("tools", tools).put("tool_choice", "auto");
        JSONObject response = post(join(base, "chat/completions"), request,
                new String[][]{{"Authorization", "Bearer " + key}});
        JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String content = message.optString("content", "");
        List<ToolCall> calls = new ArrayList<>();
        JSONArray rawCalls = message.optJSONArray("tool_calls");
        JSONArray canonicalCalls = new JSONArray();
        if (rawCalls != null) for (int i = 0; i < rawCalls.length(); i++) {
            JSONObject raw = rawCalls.getJSONObject(i);
            JSONObject function = raw.getJSONObject("function");
            String id = raw.optString("id", "call_" + i);
            JSONObject arguments = parseArguments(function.optString("arguments", "{}"));
            calls.add(new ToolCall(id, function.getString("name"), arguments));
            canonicalCalls.put(new JSONObject().put("id", id).put("name", function.getString("name"))
                    .put("arguments", arguments));
        }
        JSONObject canonical = new JSONObject().put("role", "assistant").put("content", content)
                .put("_toolCalls", canonicalCalls);
        return new Completion(content, calls, canonical);
    }

    private Completion anthropic(String base, String model, String key, JSONArray conversation,
                                 JSONArray tools) throws Exception {
        String system = systemText(conversation);
        JSONObject request = new JSONObject().put("model", model).put("max_tokens", 4096)
                .put("system", system).put("messages", anthropicMessages(conversation));
        if (tools.length() > 0) request.put("tools", anthropicTools(tools));
        JSONObject response = post(join(base, "v1/messages"), request,
                new String[][]{{"x-api-key", key}, {"anthropic-version", "2023-06-01"}});
        JSONArray blocks = response.getJSONArray("content");
        StringBuilder content = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JSONArray canonicalCalls = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if ("text".equals(block.optString("type"))) content.append(block.optString("text"));
            if ("tool_use".equals(block.optString("type"))) {
                ToolCall call = new ToolCall(block.getString("id"), block.getString("name"),
                        block.optJSONObject("input") == null ? new JSONObject() : block.getJSONObject("input"));
                calls.add(call);
                canonicalCalls.put(new JSONObject().put("id", call.id).put("name", call.name)
                        .put("arguments", call.arguments));
            }
        }
        JSONObject canonical = new JSONObject().put("role", "assistant").put("content", content.toString())
                .put("_toolCalls", canonicalCalls);
        return new Completion(content.toString(), calls, canonical);
    }

    private Completion gemini(String base, String model, String key, JSONArray conversation,
                              JSONArray tools) throws Exception {
        JSONObject request = new JSONObject().put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", systemText(conversation)))))
                .put("contents", geminiMessages(conversation))
                .put("generationConfig", new JSONObject().put("temperature", 0.2));
        if (tools.length() > 0) request.put("tools", new JSONArray().put(
                new JSONObject().put("functionDeclarations", geminiTools(tools))));
        String url = join(base, "v1beta/models/" + URLEncoder.encode(model, "UTF-8")
                + ":generateContent");
        JSONObject response = post(url, request, new String[][]{{"x-goog-api-key", key}});
        JSONArray parts = response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts");
        StringBuilder content = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JSONArray canonicalCalls = new JSONArray();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part.has("text")) content.append(part.optString("text"));
            JSONObject function = part.optJSONObject("functionCall");
            if (function != null) {
                String id = "gemini_" + System.nanoTime() + "_" + i;
                JSONObject args = function.optJSONObject("args") == null ? new JSONObject() : function.getJSONObject("args");
                ToolCall call = new ToolCall(id, function.getString("name"), args);
                calls.add(call);
                canonicalCalls.put(new JSONObject().put("id", id).put("name", call.name).put("arguments", args));
            }
        }
        JSONObject canonical = new JSONObject().put("role", "assistant").put("content", content.toString())
                .put("_toolCalls", canonicalCalls);
        return new Completion(content.toString(), calls, canonical);
    }

    private static JSONArray openAiMessages(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject message = source.getJSONObject(i);
            String role = message.getString("role");
            JSONObject target = new JSONObject().put("role", role).put("content", message.optString("content", ""));
            if ("assistant".equals(role)) {
                JSONArray canonicalCalls = message.optJSONArray("_toolCalls");
                if (canonicalCalls != null && canonicalCalls.length() > 0) {
                    JSONArray calls = new JSONArray();
                    for (int j = 0; j < canonicalCalls.length(); j++) {
                        JSONObject call = canonicalCalls.getJSONObject(j);
                        calls.put(new JSONObject().put("id", call.getString("id")).put("type", "function")
                                .put("function", new JSONObject().put("name", call.getString("name"))
                                        .put("arguments", call.getJSONObject("arguments").toString())));
                    }
                    target.put("tool_calls", calls);
                }
            } else if ("tool".equals(role)) {
                target.put("tool_call_id", message.getString("toolCallId"));
            }
            result.put(target);
        }
        return result;
    }

    private static JSONArray anthropicMessages(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject message = source.getJSONObject(i);
            String role = message.getString("role");
            if ("system".equals(role)) continue;
            if ("tool".equals(role)) {
                result.put(new JSONObject().put("role", "user").put("content", new JSONArray().put(
                        new JSONObject().put("type", "tool_result").put("tool_use_id", message.getString("toolCallId"))
                                .put("content", message.optString("content")))));
                continue;
            }
            JSONArray blocks = new JSONArray();
            if (!message.optString("content").isBlank()) blocks.put(new JSONObject().put("type", "text")
                    .put("text", message.optString("content")));
            JSONArray calls = message.optJSONArray("_toolCalls");
            if (calls != null) for (int j = 0; j < calls.length(); j++) {
                JSONObject call = calls.getJSONObject(j);
                blocks.put(new JSONObject().put("type", "tool_use").put("id", call.getString("id"))
                        .put("name", call.getString("name")).put("input", call.getJSONObject("arguments")));
            }
            result.put(new JSONObject().put("role", "assistant".equals(role) ? "assistant" : "user")
                    .put("content", blocks));
        }
        return result;
    }

    private static JSONArray geminiMessages(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject message = source.getJSONObject(i);
            String role = message.getString("role");
            if ("system".equals(role)) continue;
            JSONArray parts = new JSONArray();
            if ("tool".equals(role)) {
                parts.put(new JSONObject().put("functionResponse", new JSONObject()
                        .put("name", message.optString("toolName")).put("response", new JSONObject()
                                .put("result", message.optString("content")))));
                role = "user";
            } else {
                if (!message.optString("content").isBlank()) parts.put(new JSONObject().put("text", message.optString("content")));
                JSONArray calls = message.optJSONArray("_toolCalls");
                if (calls != null) for (int j = 0; j < calls.length(); j++) {
                    JSONObject call = calls.getJSONObject(j);
                    parts.put(new JSONObject().put("functionCall", new JSONObject().put("name", call.getString("name"))
                            .put("args", call.getJSONObject("arguments"))));
                }
            }
            result.put(new JSONObject().put("role", "assistant".equals(role) ? "model" : "user").put("parts", parts));
        }
        return result;
    }

    private static JSONArray anthropicTools(JSONArray tools) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject fn = tools.getJSONObject(i).getJSONObject("function");
            result.put(new JSONObject().put("name", fn.getString("name"))
                    .put("description", fn.optString("description")).put("input_schema", fn.getJSONObject("parameters")));
        }
        return result;
    }

    private static JSONArray geminiTools(JSONArray tools) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject fn = tools.getJSONObject(i).getJSONObject("function");
            result.put(new JSONObject().put("name", fn.getString("name"))
                    .put("description", fn.optString("description")).put("parameters", fn.getJSONObject("parameters")));
        }
        return result;
    }

    private static String systemText(JSONArray source) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < source.length(); i++) if ("system".equals(source.getJSONObject(i).optString("role"))) {
            result.append(source.getJSONObject(i).optString("content")).append('\n');
        }
        return result.toString().trim();
    }

    private static JSONObject parseArguments(String value) {
        try { return new JSONObject(value); } catch (Exception ignored) { return new JSONObject(); }
    }

    private JSONObject post(String url, JSONObject request, String[][] headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        activeConnection = connection;
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "DSH-Android/1.1");
        for (String[] header : headers) connection.setRequestProperty(header[0], header[1]);
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        catch (Exception error) {
            connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
            throw error;
        }
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder response = new StringBuilder();
            if (stream != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                    if (response.length() > 2 * 1024 * 1024) throw new IllegalStateException("模型响应超过 2 MiB 限制");
                }
            }
            if (status < 200 || status >= 300) {
                String detail = response.length() > 1000 ? response.substring(0, 1000) : response.toString();
                throw new IllegalStateException("模型 API 返回 HTTP " + status + ": " + detail);
            }
            return new JSONObject(response.toString());
        } finally {
            connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
        }
    }

    private static String join(String base, String path) {
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String right = path.startsWith("/") ? path.substring(1) : path;
        return left + "/" + right;
    }
}
