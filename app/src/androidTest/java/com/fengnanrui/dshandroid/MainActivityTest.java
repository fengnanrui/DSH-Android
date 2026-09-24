package com.fengnanrui.dshandroid;

import android.test.ActivityInstrumentationTestCase2;
import android.app.Instrumentation.ActivityMonitor;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.EditText;

@SuppressWarnings("deprecation")
public final class MainActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public MainActivityTest() { super(MainActivity.class); }

    public void testAppIsNativeAndStandalone() {
        MainActivity activity = getActivity();
        View root = activity.findViewById(android.R.id.content);
        assertFalse("The standalone app must not contain a WebView", containsWebView(root));
        assertTrue("Native session navigation must be visible", containsText(root, "会话"));
        assertTrue("Standalone status must be visible", containsText(root, "本机独立运行"));
    }

    public void testFullSettingsSurfacesAreReachable() {
        MainActivity activity = getActivity();
        clickText(activity, "设置");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "通用设置"));
        assertTrue(containsText(activity.findViewById(android.R.id.content), "Agent 预设"));

        clickText(activity, "模型");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "DeepSeek"));
        assertTrue(containsText(activity.findViewById(android.R.id.content), "添加自定义提供方"));

        clickText(activity, "插件");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "终端"));
        assertTrue(containsText(activity.findViewById(android.R.id.content), "Agent 循环"));
        clickText(activity, "插件列表");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "原生可用 20"));
        assertTrue(containsText(activity.findViewById(android.R.id.content), "显示全部兼容标识"));

        clickText(activity, "Agent 预设");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "标准模式"));
        assertTrue(containsText(activity.findViewById(android.R.id.content), "PTC 模式"));
        assertTrue(containsText(activity.findViewById(android.R.id.content), "极简模式"));
        assertTrue(containsText(activity.findViewById(android.R.id.content), "创造模式"));
    }

    public void testPlanGoalJobsAndWorkflowSurfacesAreReachable() {
        MainActivity activity = getActivity();
        clickText(activity, "任务");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "任务计划"));
        clickText(activity, "目标");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "新建目标"));
        clickText(activity, "Jobs");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "启动 Job"));
        clickText(activity, "工作流");
        assertTrue(containsText(activity.findViewById(android.R.id.content), "创建工作流"));
    }

    public void testActivityRecreationRetainsControllerAndChatDraft() {
        MainActivity activity = getActivity();
        clickText(activity, "＋ 新会话");
        EditText editor = findEditor(activity.findViewById(android.R.id.content));
        assertNotNull(editor);
        getInstrumentation().runOnMainSync(() -> editor.setText("retained draft fixture"));
        Object owner = activity.onRetainNonConfigurationInstance();
        ActivityMonitor monitor = new ActivityMonitor(MainActivity.class.getName(), null, false);
        getInstrumentation().addMonitor(monitor);
        MainActivity recreated = null;
        try {
            getInstrumentation().runOnMainSync(activity::recreate);
            recreated = (MainActivity) monitor.waitForActivityWithTimeout(3000);
            assertNotNull("Activity must recreate", recreated);
            getInstrumentation().waitForIdleSync();
            assertSame(owner, recreated.onRetainNonConfigurationInstance());
            EditText restored = findEditor(recreated.findViewById(android.R.id.content));
            assertNotNull(restored);
            assertEquals("retained draft fixture", restored.getText().toString());
        } finally {
            getInstrumentation().removeMonitor(monitor);
            if (recreated != null) getInstrumentation().runOnMainSync(recreated::finish);
        }
    }

    public void testRestoredDraftAppearsWithoutLeavingTheConversation() {
        MainActivity activity = getActivity();
        clickText(activity, "＋ 新会话");
        SessionController controller = (SessionController) activity.onRetainNonConfigurationInstance();
        AppStore.Session session = controller.store.sessions().get(0);
        EditText editor = findEditor(activity.findViewById(android.R.id.content));
        assertNotNull(editor);
        getInstrumentation().runOnMainSync(() -> {
            editor.setText("new draft");
            controller.drafts.put(session.id, "new draft\n\nunsent queued message");
            activity.changed(session.id, false);
        });
        assertEquals("new draft\n\nunsent queued message", editor.getText().toString());
        getInstrumentation().runOnMainSync(() -> {
            editor.setSelection(2);
            activity.changed(session.id, false);
        });
        assertEquals("Status updates must not move the cursor", 2, editor.getSelectionStart());
    }

    private EditText findEditor(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditor(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void clickText(MainActivity activity, String value) {
        View target = findText(activity.findViewById(android.R.id.content), value);
        assertNotNull("Missing clickable text: " + value, target);
        getInstrumentation().runOnMainSync(target::performClick);
        getInstrumentation().waitForIdleSync();
    }

    private View findText(View view, String expected) {
        if (view instanceof TextView && expected.equals(((TextView) view).getText().toString()) && view.isClickable()) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findText(group.getChildAt(i), expected);
            if (found != null) return found;
        }
        return null;
    }

    private boolean containsWebView(View view) {
        if (view instanceof WebView) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (containsWebView(group.getChildAt(i))) return true;
        return false;
    }

    private boolean containsText(View view, String expected) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), expected)) return true;
        return false;
    }
}
