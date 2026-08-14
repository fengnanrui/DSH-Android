package com.fengnanrui.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProviderRegistryTest {
    @Test public void defaultsToDeepSeek() {
        ProviderRegistry.Provider provider = ProviderRegistry.byId("missing");
        assertEquals("deepseek", provider.id());
        assertEquals(ProviderRegistry.Protocol.OPENAI, provider.protocol());
    }

    @Test public void everyProviderUsesHttps() {
        for (ProviderRegistry.Provider provider : ProviderRegistry.all()) {
            assertTrue(provider.name(), provider.baseUrl().startsWith("https://"));
            assertTrue(provider.name(), !provider.model().isBlank());
        }
    }

    @Test public void includesNativeProtocols() {
        assertEquals(ProviderRegistry.Protocol.ANTHROPIC, ProviderRegistry.byId("anthropic").protocol());
        assertEquals(ProviderRegistry.Protocol.GEMINI, ProviderRegistry.byId("gemini").protocol());
    }
}
