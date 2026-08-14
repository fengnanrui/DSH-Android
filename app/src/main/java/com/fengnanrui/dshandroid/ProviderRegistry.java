package com.fengnanrui.dshandroid;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Model providers supported directly on-device. */
public final class ProviderRegistry {
    public enum Protocol { OPENAI, ANTHROPIC, GEMINI }

    public record Provider(String id, String name, String baseUrl, String model,
                           Protocol protocol) {
        @Override public String toString() { return name; }
    }

    private static final List<Provider> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            new Provider("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash", Protocol.OPENAI),
            new Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-5-mini", Protocol.OPENAI),
            new Provider("anthropic", "Anthropic", "https://api.anthropic.com", "claude-sonnet-5", Protocol.ANTHROPIC),
            new Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com", "gemini-3.5-flash", Protocol.GEMINI),
            new Provider("xai", "xAI", "https://api.x.ai/v1", "grok-4-fast", Protocol.OPENAI),
            new Provider("moonshot", "Moonshot / Kimi", "https://api.moonshot.cn/v1", "kimi-k2.5", Protocol.OPENAI),
            new Provider("minimax", "MiniMax", "https://api.minimax.io/v1", "MiniMax-M2.1", Protocol.OPENAI),
            new Provider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7", Protocol.OPENAI),
            new Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-large-latest", Protocol.OPENAI),
            new Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat", Protocol.OPENAI),
            new Provider("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", Protocol.OPENAI),
            new Provider("together", "Together AI", "https://api.together.xyz/v1", "deepseek-ai/DeepSeek-V3", Protocol.OPENAI),
            new Provider("custom", "自定义 OpenAI 兼容", "https://example.com/v1", "model-name", Protocol.OPENAI)
    ));

    private ProviderRegistry() {}

    public static List<Provider> all() { return PROVIDERS; }

    public static Provider byId(String id) {
        for (Provider provider : PROVIDERS) {
            if (provider.id().equals(id)) return provider;
        }
        return PROVIDERS.get(0);
    }

    public static int indexOf(String id) {
        for (int i = 0; i < PROVIDERS.size(); i++) {
            if (PROVIDERS.get(i).id().equals(id)) return i;
        }
        return 0;
    }
}
