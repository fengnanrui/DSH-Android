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
public final class ModelClient implements ModelTransport {
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
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalStateException("请先在设置中保存 API Key");
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
        if (message.has("reasoning_content")) canonical.put("reasoning_content", message.get("reasoning_content"));
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
                .put("_toolCalls", canonicalCalls).put("_anthropicBlocks", blocks);
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
        return geminiCompletion(response);
    }

    static Completion geminiCompletion(JSONObject response) throws Exception {
        JSONArray parts = response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts");
        StringBuilder content = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JSONArray canonicalCalls = new JSONArray();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part.has("text") && !part.optBoolean("thought")) content.append(part.optString("text"));
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
                .put("_toolCalls", canonicalCalls).put("_geminiParts", parts);
        return new Completion(content.toString(), calls, canonical);
    }

    static JSONArray openAiMessages(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject message = source.getJSONObject(i);
            String role = message.getString("role");
            JSONObject target = new JSONObject().put("role", role).put("content", message.optString("content", ""));
            if ("assistant".equals(role)) {
                if (message.has("reasoning_content")) target.put("reasoning_content", message.get("reasoning_content"));
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

    static JSONArray anthropicMessages(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject message = source.getJSONObject(i);
            String role = message.getString("role");
            if ("system".equals(role)) continue;
            if ("tool".equals(role)) {
                JSONArray results;
                if (i > 0 && "tool".equals(source.getJSONObject(i - 1).optString("role")))
                    results = result.getJSONObject(result.length() - 1).getJSONArray("content");
                else {
                    results = new JSONArray();
                    result.put(new JSONObject().put("role", "user").put("content", results));
                }
                results.put(new JSONObject().put("type", "tool_result").put("tool_use_id", message.getString("toolCallId"))
                        .put("content", message.optString("content")));
                continue;
            }
            if ("assistant".equals(role) && message.has("_anthropicBlocks")) {
                result.put(new JSONObject().put("role", role).put("content", message.getJSONArray("_anthropicBlocks")));
                continue;
            }
            JSONArray blocks = new JSONArray();
            if (!message.optString("content").trim().isEmpty()) blocks.put(new JSONObject().put("type", "text")
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

    static JSONArray geminiMessages(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject message = source.getJSONObject(i);
            String role = message.getString("role");
            if ("system".equals(role)) continue;
            if ("assistant".equals(role) && message.has("_geminiParts")) {
                result.put(new JSONObject().put("role", "model").put("parts", message.getJSONArray("_geminiParts")));
                continue;
            }
            JSONArray parts = new JSONArray();
            if ("tool".equals(role)) {
                parts.put(new JSONObject().put("functionResponse", new JSONObject()
                        .put("name", message.optString("toolName")).put("response", new JSONObject()
                                .put("result", message.optString("content")))));
                role = "user";
                if (i > 0 && "tool".equals(source.getJSONObject(i - 1).optString("role"))) {
                    result.getJSONObject(result.length() - 1).getJSONArray("parts").put(parts.getJSONObject(0));
                    continue;
                }
            } else {
                if (!message.optString("content").trim().isEmpty()) parts.put(new JSONObject().put("text", message.optString("content")));
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

    static JSONObject parseArguments(String value) throws Exception {
        return new JSONObject(value);
    }

    private JSONObject post(String url, JSONObject request, String[][] headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        activeConnection = connection;
        if (Thread.currentThread().isInterrupted()) { connection.disconnect(); activeConnection = null; throw new InterruptedException(); }
        connection.setRequestMethod("POST");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "DSH-Android/" + BuildConfig.VERSION_NAME);
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
            String response = "";
            if (stream != null) try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                response = BoundedText.read(reader, 2 * 1024 * 1024 + 1, false);
                if (response.length() > 2 * 1024 * 1024) throw new IllegalStateException("模型响应超过 2 MiB 限制");
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("模型 API 返回 HTTP " + status + ": " + errorDetail(response, headers));
            }
            return new JSONObject(response);
        } finally {
            connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
        }
    }

    static String errorDetail(String response, String[][] headers) {
        String detail = response;
        // Redact before clipping: a credential crossing the display limit must not leak its prefix.
        for (String[] header : headers) {
            if (header[1].isEmpty()) continue;
            detail = detail.replace(header[1], "[redacted]");
            if (header[1].startsWith("Bearer ") && header[1].length() > 7)
                detail = detail.replace(header[1].substring(7), "[redacted]");
        }
        return detail.length() > 1000 ? detail.substring(0, 1000) : detail;
    }

    static String join(String base, String path) {
        String left = base;
        while (left.endsWith("/")) left = left.substring(0, left.length() - 1);
        String right = path.startsWith("/") ? path.substring(1) : path;
        int slash = right.indexOf('/');
        if (slash > 0 && left.endsWith("/" + right.substring(0, slash))) right = right.substring(slash + 1);
        return left + "/" + right;
    }
}
