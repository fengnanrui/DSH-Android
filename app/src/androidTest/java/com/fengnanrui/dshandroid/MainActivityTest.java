package com.fengnanrui.dshandroid;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

@SuppressWarnings("deprecation")
public final class MainActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public MainActivityTest() {
        super(MainActivity.class);
    }

    public void testWebViewCapabilitiesAndSecurityDefaults() {
        MainActivity activity = getActivity();
        final boolean[] checks = new boolean[6];
        final int[] mixedContentMode = new int[1];
        getInstrumentation().runOnMainSync(() -> {
            WebView webView = findWebView(activity.findViewById(android.R.id.content));
            checks[0] = webView != null;
            if (webView == null) return;
            WebSettings settings = webView.getSettings();
            checks[1] = settings.getJavaScriptEnabled();
            checks[2] = settings.getDomStorageEnabled();
            checks[3] = settings.getAllowContentAccess();
            checks[4] = !CookieManager.getInstance().acceptThirdPartyCookies(webView);
            checks[5] = settings.getUserAgentString().contains("DSH-Android/0.1.0");
            mixedContentMode[0] = settings.getMixedContentMode();
        });

        assertTrue("MainActivity must contain its Harness WebView", checks[0]);
        assertTrue("Harness requires JavaScript", checks[1]);
        assertTrue("Harness requires DOM storage", checks[2]);
        assertTrue("Android file uploads require content access", checks[3]);
        assertEquals("Mixed content must stay blocked", WebSettings.MIXED_CONTENT_NEVER_ALLOW,
                mixedContentMode[0]);
        assertTrue("Third-party cookies must stay blocked", checks[4]);
        assertTrue("User-Agent must identify the mobile shell", checks[5]);
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            WebView found = findWebView(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }
}
