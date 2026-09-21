package com.fengnanrui.dshandroid;

import org.json.JSONArray;

/** External model boundary; the agent loop and persistence remain native. */
interface ModelTransport {
    ModelClient.Completion complete(ProviderRegistry.Provider provider, String baseUrl, String model,
                                    String apiKey, JSONArray conversation, JSONArray tools) throws Exception;
    void cancel();
}
