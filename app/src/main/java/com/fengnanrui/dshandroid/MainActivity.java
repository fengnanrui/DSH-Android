package com.fengnanrui.dshandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/** Native, standalone phone UI. Deliberately contains no WebView. */
public final class MainActivity extends Activity {
    private static final int PICK_ATTACHMENT = 41;
    private static final int BLUE = Color.rgb(52, 91, 255);
    private int INK;
    private int MUTED;
    private int SURFACE;
    private int LINE;
    private int CARD;
    private boolean darkMode;

    private AppStore store;
    private SecretStore secrets;
    private AgentRuntime runtime;
    private AppStore.Session activeSession;
    private LinearLayout root;
    private LinearLayout header;
    private FrameLayout content;
    private LinearLayout bottomBar;
    private String screen = "sessions";
    private EditText chatInput;
    private LinearLayout messagesColumn;
    private TextView chatStatus;
    private Button sendButton;
    private String pendingAttachment = "";
    private boolean agentRunning;
    private final ArrayDeque<String> promptQueue = new ArrayDeque<>();

    @Override protected void onCreate(Bundle state) {
        String requestedTheme = getSharedPreferences("dsh_settings", MODE_PRIVATE).getString("theme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        darkMode = "dark".equals(requestedTheme) || ("system".equals(requestedTheme) && systemDark);
        setTheme(darkMode ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        applyPalette();
        Window window = getWindow();
        window.setStatusBarColor(CARD);
        window.setNavigationBarColor(CARD);
        window.getDecorView().setSystemUiVisibility(darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new AppStore(this);
        secrets = new SecretStore(this);
        runtime = new AgentRuntime(store, secrets);
        activeSession = store.sessions().get(0);
        buildShell();
        showSessions();
    }

    private void applyPalette() {
        INK = darkMode ? Color.rgb(242, 244, 248) : Color.rgb(29, 31, 36);
        MUTED = darkMode ? Color.rgb(166, 172, 184) : Color.rgb(104, 109, 120);
        SURFACE = darkMode ? Color.rgb(16, 18, 23) : Color.rgb(247, 248, 251);
        LINE = darkMode ? Color.rgb(49, 53, 63) : Color.rgb(226, 229, 235);
        CARD = darkMode ? Color.rgb(28, 31, 38) : Color.WHITE;
    }

    @Override protected void onDestroy() {
        runtime.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if ("chat".equals(screen)) { showSessions(); return; }
        if (!"sessions".equals(screen)) { showSessions(); return; }
        super.onBackPressed();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        setContentView(root);

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), 0, dp(12), 0);
        header.setBackgroundColor(CARD);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bottomBar.setMinimumHeight(dp(62));
        bottomBar.setBackgroundColor(CARD);
        root.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(62)));
        addNav("会话", "sessions", this::showSessions);
        addNav("工作区", "workspace", this::showWorkspace);
        addNav("任务", "tasks", this::showTasks);
        addNav("设置", "settings", this::showSettings);
        // NexusAI and some Android 9 gesture overlays draw above the system navigation inset.
        View gestureSafeArea = new View(this);
        gestureSafeArea.setBackgroundColor(CARD);
        root.addView(gestureSafeArea, new LinearLayout.LayoutParams(-1, dp(30)));
    }

    private void addNav(String label, String id, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(13);
        item.setGravity(Gravity.CENTER);
        item.setTag(id);
        item.setContentDescription(label);
        item.setOnClickListener(v -> action.run());
        bottomBar.addView(item, new LinearLayout.LayoutParams(0, dp(54), 1));
    }

    private void selectNav(String id) {
        for (int i = 0; i < bottomBar.getChildCount(); i++) {
            TextView item = (TextView) bottomBar.getChildAt(i);
            boolean active = id.equals(item.getTag()) || ("chat".equals(id) && "sessions".equals(item.getTag()));
            item.setTextColor(active ? BLUE : MUTED);
            item.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            item.setBackground(active ? round(Color.rgb(236, 240, 255), 12) : null);
        }
    }

    private void setHeader(String title, String action, View.OnClickListener listener) {
        header.removeAllViews();
        TextView titleView = text(title, 21, INK, true);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView button = text(action, 15, BLUE, true);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(12), dp(8), dp(12), dp(8));
            button.setBackground(round(Color.rgb(237, 241, 255), 12));
            button.setOnClickListener(listener);
            header.addView(button, new LinearLayout.LayoutParams(-2, dp(40)));
        }
    }

    private void showSessions() {
        screen = "sessions";
        selectNav(screen);
        setHeader("DSH Android", "＋ 新会话", v -> {
            activeSession = store.createSession();
            showChat();
        });
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = column(dp(16));
        column.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));

        TextView localBadge = text("●  本机独立运行", 13, Color.rgb(31, 133, 91), true);
        localBadge.setPadding(dp(14), dp(10), dp(14), dp(10));
        localBadge.setBackground(round(Color.rgb(230, 247, 239), 14));
        column.addView(localBadge, matchWrap());

        TextView hint = text("会话、工作区和 Agent 都保存在这台手机上。模型请求由手机直接发给你配置的供应商。", 14, MUTED, false);
        hint.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams hintParams = matchWrap(); hintParams.topMargin = dp(12);
        column.addView(hint, hintParams);

        List<AppStore.Session> sessions = store.sessions();
        for (AppStore.Session session : sessions) {
            LinearLayout card = column(dp(4));
            card.setPadding(dp(16), dp(14), dp(12), dp(12));
            card.setBackground(round(CARD, 16));
            card.setElevation(dp(1));
            TextView title = text(session.title, 17, INK, true);
            card.addView(title, matchWrap());
            String meta = session.messages.size() + " 条记录  ·  " + DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(new Date(session.updatedAt));
            TextView subtitle = text(meta, 12, MUTED, false);
            LinearLayout.LayoutParams subParams = matchWrap(); subParams.topMargin = dp(7);
            card.addView(subtitle, subParams);
            card.setOnClickListener(v -> { activeSession = session; showChat(); });
            card.setOnLongClickListener(v -> { confirmDelete(session); return true; });
            LinearLayout.LayoutParams cardParams = matchWrap(); cardParams.topMargin = dp(12);
            column.addView(card, cardParams);
        }
        content.addView(scroll, matchParent());
    }

    private void showChat() {
        screen = "chat";
        selectNav(screen);
        setHeader(activeSession.title, "会话列表", v -> showSessions());
        content.removeAllViews();
        LinearLayout page = column(0);
        messagesColumn = column(dp(10));
        messagesColumn.setPadding(dp(12), dp(14), dp(12), dp(18));
        ScrollView messagesScroll = new ScrollView(this);
        messagesScroll.setFillViewport(true);
        messagesScroll.addView(messagesColumn, new ScrollView.LayoutParams(-1, -2));
        page.addView(messagesScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderMessages();

        chatStatus = text(providerSummary(), 12, MUTED, false);
        chatStatus.setPadding(dp(14), dp(5), dp(14), dp(3));
        page.addView(chatStatus, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(8), dp(6), dp(8), dp(8));
        composer.setBackgroundColor(CARD);
        Button attach = smallButton("＋");
        attach.setContentDescription("添加附件");
        attach.setOnClickListener(v -> pickAttachment());
        composer.addView(attach, new LinearLayout.LayoutParams(dp(44), dp(48)));
        chatInput = new EditText(this);
        chatInput.setHint("给本机 Agent 发消息…");
        chatInput.setTextSize(16);
        chatInput.setTextColor(INK);
        chatInput.setHintTextColor(MUTED);
        chatInput.setMinLines(1);
        chatInput.setMaxLines(5);
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInput.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        chatInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEND) return false;
            if (agentRunning && "newline".equals(store.enterBehavior())) return false;
            send(); return true;
        });
        chatInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        chatInput.setBackground(round(Color.rgb(241, 243, 247), 18));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -2, 1);
        inputParams.leftMargin = dp(6); inputParams.rightMargin = dp(7);
        composer.addView(chatInput, inputParams);
        sendButton = smallButton("发送");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setBackground(round(BLUE, 16));
        sendButton.setOnClickListener(v -> send());
        composer.addView(sendButton, new LinearLayout.LayoutParams(dp(66), dp(48)));
        page.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        content.addView(page, matchParent());
        messagesScroll.post(() -> messagesScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void renderMessages() {
        messagesColumn.removeAllViews();
        if (activeSession.messages.isEmpty()) {
            LinearLayout welcome = column(dp(8));
            welcome.setPadding(dp(18), dp(18), dp(18), dp(18));
            welcome.setBackground(round(CARD, 18));
            welcome.addView(text("手机上的完整 Agent", 20, INK, true), matchWrap());
            welcome.addView(text("可以让它读取或修改本地工作区、搜索文件、执行经你批准的命令、访问网页并维护任务计划。", 14, MUTED, false), matchWrap());
            messagesColumn.addView(welcome, matchWrap());
            return;
        }
        for (AppStore.Message message : activeSession.messages) addMessageBubble(message);
    }

    private void addMessageBubble(AppStore.Message message) {
        boolean user = "user".equals(message.role);
        boolean tool = "tool".equals(message.role);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(user ? Gravity.END : Gravity.START);
        TextView bubble = text((tool ? "工具 · " + message.toolName + "\n" : "") + message.content,
                tool ? 12 : 15, tool ? MUTED : (user ? Color.WHITE : INK), false);
        bubble.setTextIsSelectable(true);
        bubble.setLineSpacing(0, 1.12f);
        bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
        bubble.setBackground(round(tool ? (darkMode ? Color.rgb(43, 47, 57) : Color.rgb(237, 239, 244)) : (user ? BLUE : CARD), 16));
        row.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(9);
        messagesColumn.addView(row, rowParams);
    }

    private void send() {
        String prompt = chatInput.getText().toString().trim();
        if (!pendingAttachment.isBlank()) prompt = (prompt.isBlank() ? "请查看这个附件" : prompt)
                + "\n\n[附件已导入工作区: " + pendingAttachment + "]";
        if (prompt.isBlank()) return;
        if (agentRunning) {
            promptQueue.add(prompt); chatInput.setText("");
            chatStatus.setText("已排队 " + promptQueue.size() + " 条消息"); return;
        }
        if (prompt.startsWith("/") && handleLocalCommand(prompt)) return;
        chatInput.setText("");
        pendingAttachment = "";
        setRunning(true);
        runtime.run(activeSession, prompt, new AgentRuntime.Callback() {
            @Override public void onStatus(String status) { runOnUiThread(() -> chatStatus.setText(status)); }
            @Override public void onMessage(AppStore.Message message) { runOnUiThread(() -> {
                addMessageBubble(message);
                setHeader(activeSession.title, "会话列表", v -> showSessions());
            }); }
            @Override public void onApproval(String tool, String arguments, AgentRuntime.ApprovalDecision decision) {
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("允许工具：" + tool + "？")
                        .setMessage(arguments + "\n\n此操作将在手机应用沙箱中执行。")
                        .setCancelable(false)
                        .setNegativeButton("拒绝", (d, w) -> decision.resolve(false))
                        .setPositiveButton("允许一次", (d, w) -> decision.resolve(true)).show());
            }
            @Override public void onQuestion(String question, AgentRuntime.UserAnswer answer) {
                runOnUiThread(() -> {
                    EditText input = multiline("请输入回答", "", 3);
                    new AlertDialog.Builder(MainActivity.this).setTitle("Agent 需要你的回答").setMessage(question)
                            .setView(input).setCancelable(false)
                            .setNegativeButton("取消", (d, w) -> answer.resolve(""))
                            .setPositiveButton("回答", (d, w) -> answer.resolve(input.getText().toString())).show();
                });
            }
            @Override public void onFinished() { runOnUiThread(() -> setRunning(false)); }
            @Override public void onError(String message) { runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                chatStatus.setText("出错：" + message);
            }); }
        });
    }

    private boolean handleLocalCommand(String command) {
        if ("/settings".equals(command)) { showSettings(); return true; }
        if ("/stop".equals(command)) { runtime.cancel(); return true; }
        if ("/new".equals(command) || "/clear".equals(command)) {
            activeSession = store.createSession(); showChat(); return true;
        }
        if (command.startsWith("/plan ")) {
            store.addPlan(activeSession, command.substring(6).trim());
            chatInput.setText("");
            Toast.makeText(this, "已加入任务计划", Toast.LENGTH_SHORT).show();
            return true;
        }
        if ("/help".equals(command)) {
            AppStore.Message help = new AppStore.Message("assistant",
                    "本地命令：/new 新会话，/plan 步骤 添加任务，/settings 打开设置，/stop 停止 Agent。");
            store.addMessage(activeSession, help); chatInput.setText(""); addMessageBubble(help);
            return true;
        }
        return false;
    }

    private void setRunning(boolean running) {
        if (sendButton == null) return;
        agentRunning = running;
        sendButton.setText(running ? "停止" : "发送");
        sendButton.setOnClickListener(running ? v -> runtime.cancel() : v -> send());
        chatInput.setEnabled(true);
        if (!running && !promptQueue.isEmpty()) {
            String next = promptQueue.removeFirst();
            chatInput.post(() -> { chatInput.setText(next); send(); });
        }
    }

    private void showWorkspace() {
        screen = "workspace";
        selectNav(screen);
        setHeader("本地工作区", "导入文件", v -> pickAttachment());
        content.removeAllViews();
        showDirectory(store.workspace(), "");
    }

    private void showDirectory(File directory, String relative) {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = column(dp(8));
        column.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        TextView path = text("工作区 / " + (relative.isBlank() ? "" : relative), 13, MUTED, false);
        path.setPadding(dp(8), dp(5), dp(8), dp(9));
        column.addView(path, matchWrap());
        if (!relative.isBlank()) {
            TextView up = fileRow("← 上一级", true);
            File parent = directory.getParentFile();
            String parentRelative = relative.contains("/") ? relative.substring(0, relative.lastIndexOf('/')) : "";
            up.setOnClickListener(v -> showDirectory(parent, parentRelative));
            column.addView(up, matchWrap());
        }
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) column.addView(text("工作区还是空的。可导入文件，或让 Agent 创建。", 14, MUTED, false), matchWrap());
        else for (File file : files) {
            String nextRelative = relative.isBlank() ? file.getName() : relative + "/" + file.getName();
            TextView row = fileRow((file.isDirectory() ? "📁  " : "📄  ") + file.getName(), file.isDirectory());
            row.setOnClickListener(v -> {
                if (file.isDirectory()) showDirectory(file, nextRelative); else showFile(file, nextRelative);
            });
            column.addView(row, matchWrap());
        }
        content.addView(scroll, matchParent());
    }

    private void showFile(File file, String relative) {
        try {
            MobileTools tools = new MobileTools(store.workspace(), store, activeSession);
            String value = tools.execute("read_file", new org.json.JSONObject().put("path", relative));
            setHeader(file.getName(), "返回工作区", v -> showWorkspace());
            ScrollView scroll = new ScrollView(this);
            TextView body = text(value, 14, INK, false);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            body.setPadding(dp(16), dp(16), dp(16), dp(24));
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            content.removeAllViews(); content.addView(scroll, matchParent());
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showTasks() {
        screen = "tasks";
        selectNav(screen);
        setHeader("运行中心", null, null);
        showPlans();
    }

    private LinearLayout taskPage(String active, String title, String empty) {
        content.removeAllViews();
        LinearLayout page = column(0);
        HorizontalScrollView tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this); tabs.setPadding(dp(10), dp(8), dp(10), dp(8));
        addSettingsTab(tabs, "计划", "plan", active, this::showPlans);
        addSettingsTab(tabs, "目标", "goals", active, this::showGoals);
        addSettingsTab(tabs, "Jobs", "jobs", active, this::showJobs);
        addSettingsTab(tabs, "工作流", "workflow", active, this::showWorkflows);
        tabScroll.addView(tabs); page.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(56)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = column(dp(9));
        column.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        column.addView(text(title, 21, INK, true), matchWrap());
        if (empty != null) {
            TextView hint = text(empty, 13, MUTED, false); hint.setPadding(0, dp(7), 0, dp(5)); column.addView(hint, matchWrap());
        }
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); content.addView(page, matchParent());
        return column;
    }

    private void showPlans() {
        LinearLayout column = taskPage("plan", "任务计划", activeSession.plan.isEmpty()
                ? "可以手动添加，也可以让 Agent 用 update_plan 生成步骤。" : null);
        Button add = secondaryButton("＋ 添加计划步骤"); add.setOnClickListener(v -> promptNewTask());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(48)); addParams.topMargin = dp(10); column.addView(add, addParams);
        if (activeSession.plan.isEmpty()) {
            return;
        }
        for (AppStore.PlanItem item : activeSession.plan) {
            TextView row = fileRow((item.done ? "☑  " : "☐  ") + item.text, false);
            if (item.done) row.setTextColor(MUTED); row.setOnClickListener(v -> { store.togglePlan(activeSession, item.id); showPlans(); });
            LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(7); column.addView(row, params);
        }
    }

    private void showGoals() {
        LinearLayout column = taskPage("goals", "目标", activeSession.goals.isEmpty() ? "目标会持久保存在当前会话中。" : null);
        Button add = secondaryButton("＋ 新建目标"); add.setOnClickListener(v -> promptNewGoal());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(48)); addParams.topMargin = dp(10); column.addView(add, addParams);
        for (AppStore.GoalItem item : activeSession.goals) {
            TextView row = fileRow((item.achieved ? "✓ 已达成  " : "◎ 进行中  ") + item.text, false);
            if (item.achieved) row.setTextColor(MUTED); row.setOnClickListener(v -> { store.toggleGoal(activeSession, item.id); showGoals(); });
            LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(7); column.addView(row, params);
        }
    }

    private void showJobs() {
        LinearLayout column = taskPage("jobs", "后台 Jobs", "在应用私有工作区运行持久命令，日志写入工作区。");
        Button add = secondaryButton("＋ 启动 Job"); add.setOnClickListener(v -> promptNewJob());
        Button refresh = secondaryButton("刷新"); refresh.setOnClickListener(v -> showJobs());
        LinearLayout actions = new LinearLayout(this); actions.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, dp(48), 1); refreshParams.leftMargin = dp(7); actions.addView(refresh, refreshParams);
        LinearLayout.LayoutParams actionsParams = matchWrap(); actionsParams.topMargin = dp(10); column.addView(actions, actionsParams);
        try {
            String value = new MobileTools(store.workspace(), store, activeSession).execute("list_jobs", new org.json.JSONObject());
            TextView jobs = fileRow(value, false); LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(9); column.addView(jobs, params);
        } catch (Exception error) { column.addView(text(error.getMessage(), 13, MUTED, false), matchWrap()); }
    }

    private void showWorkflows() {
        LinearLayout column = taskPage("workflow", "工作流", activeSession.workflows.isEmpty()
                ? "保存可重复执行的 Agent 指令；运行时仍经过当前预设和权限门。" : null);
        Button add = secondaryButton("＋ 创建工作流"); add.setOnClickListener(v -> promptNewWorkflow());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(48)); addParams.topMargin = dp(10); column.addView(add, addParams);
        for (AppStore.WorkflowItem item : activeSession.workflows) {
            LinearLayout card = column(0); card.setPadding(dp(14), dp(12), dp(14), dp(12)); card.setBackground(round(CARD, 13));
            card.addView(text(item.name + "  ▶", 16, INK, true), matchWrap()); card.addView(text(item.prompt, 13, MUTED, false), matchWrap());
            card.setOnClickListener(v -> { showChat(); chatInput.setText(item.prompt); send(); });
            LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(8); column.addView(card, params);
        }
    }

    private void showSettings() {
        screen = "settings";
        selectNav(screen);
        setHeader("设置", "导出配置", v -> showConfigExport());
        showGeneralSettings();
    }

    private LinearLayout settingsPage(String activeTab, String title, String subtitle) {
        content.removeAllViews();
        LinearLayout page = column(0);
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(10), dp(8), dp(10), dp(8));
        addSettingsTab(tabs, "通用", "general", activeTab, this::showGeneralSettings);
        addSettingsTab(tabs, "模型", "models", activeTab, this::showModelsSettings);
        addSettingsTab(tabs, "插件", "plugins", activeTab, this::showPluginsSettings);
        addSettingsTab(tabs, "Agent 预设", "presets", activeTab, this::showPresetsSettings);
        tabScroll.addView(tabs, new HorizontalScrollView.LayoutParams(-2, -1));
        page.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(56)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column(0);
        form.setPadding(dp(18), dp(16), dp(18), dp(30));
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        form.addView(text(title, 22, INK, true), matchWrap());
        TextView description = text(subtitle, 13, MUTED, false);
        description.setPadding(0, dp(7), 0, dp(10));
        form.addView(description, matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(page, matchParent());
        return form;
    }

    private void addSettingsTab(LinearLayout tabs, String title, String id, String active, Runnable action) {
        TextView tab = text(title, 14, id.equals(active) ? BLUE : MUTED, id.equals(active));
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(16), 0, dp(16), 0);
        tab.setBackground(id.equals(active) ? round(Color.rgb(236, 240, 255), 13) : null);
        tab.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(40));
        params.rightMargin = dp(5); tabs.addView(tab, params);
    }

    private void showGeneralSettings() {
        LinearLayout form = settingsPage("general", "通用设置", "新会话采用这些默认值；运行中的会话保留启动时的 Agent 预设。");

        form.addView(label("Agent 预设"), matchWrap());
        Spinner preset = new Spinner(this);
        List<AppStore.AgentPreset> presets = store.presets();
        java.util.ArrayList<String> presetNames = new java.util.ArrayList<>();
        for (AppStore.AgentPreset item : presets) presetNames.add(item.name());
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                presetNames);
        preset.setAdapter(presetAdapter);
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).id().equals(store.defaultPresetId())) preset.setSelection(i);
        form.addView(preset, new LinearLayout.LayoutParams(-1, dp(52)));

        form.addView(label("权限"), matchWrap());
        Spinner permission = spinner(new String[]{"每次询问（推荐）", "工作区写入自动允许", "全部自动允许"});
        permission.setSelection("full".equals(store.permissionMode()) ? 2 : ("workspace".equals(store.permissionMode()) ? 1 : 0));
        form.addView(permission, new LinearLayout.LayoutParams(-1, dp(52)));

        form.addView(label("语言"), matchWrap());
        Spinner language = spinner(new String[]{"中文", "English"});
        language.setSelection("en".equals(store.language()) ? 1 : 0);
        form.addView(language, new LinearLayout.LayoutParams(-1, dp(52)));

        form.addView(label("外观"), matchWrap());
        Spinner theme = spinner(new String[]{"浅色", "深色", "跟随系统"});
        theme.setSelection("dark".equals(store.theme()) ? 1 : ("system".equals(store.theme()) ? 2 : 0));
        form.addView(theme, new LinearLayout.LayoutParams(-1, dp(52)));

        form.addView(label("繁忙时 Enter 键行为"), matchWrap());
        Spinner enter = spinner(new String[]{"排队发送", "换行"});
        enter.setSelection("newline".equals(store.enterBehavior()) ? 1 : 0);
        form.addView(enter, new LinearLayout.LayoutParams(-1, dp(52)));

        form.addView(label("Agent 附加指令"), matchWrap());
        EditText instructions = multiline("例如：优先用中文，修改前先建立计划", store.agentInstructions(), 3);
        form.addView(instructions, matchWrap());

        Button save = primaryButton("保存通用设置");
        save.setOnClickListener(v -> {
            String permissionMode = permission.getSelectedItemPosition() == 2 ? "full" :
                    (permission.getSelectedItemPosition() == 1 ? "workspace" : "ask");
            String lang = language.getSelectedItemPosition() == 1 ? "en" : "zh-CN";
            String themeId = new String[]{"light", "dark", "system"}[theme.getSelectedItemPosition()];
            String enterId = enter.getSelectedItemPosition() == 1 ? "newline" : "send";
            boolean appearanceChanged = !themeId.equals(store.theme());
            store.saveGeneral(permissionMode, lang, themeId, enterId);
            store.setDefaultPreset(presets.get(preset.getSelectedItemPosition()).id());
            store.saveAgentInstructions(instructions.getText().toString());
            Toast.makeText(this, "通用设置已保存", Toast.LENGTH_SHORT).show();
            if (appearanceChanged) recreate();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(52)); saveParams.topMargin = dp(18);
        form.addView(save, saveParams);
    }

    private void showModelsSettings() {
        LinearLayout form = settingsPage("models", "模型", "支持预置与自定义 API 提供方；密钥使用 Android Keystore 加密。");
        AppStore.ProviderProfile active = store.activeProviderProfile();
        for (AppStore.ProviderProfile profile : store.providerProfiles()) {
            LinearLayout card = column(0);
            card.setPadding(dp(15), dp(13), dp(12), dp(13));
            card.setBackground(round(CARD, 15));
            LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(profile.name() + (profile.id().equals(active.id()) ? "  ●" : ""), 17,
                    profile.id().equals(active.id()) ? BLUE : INK, true);
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Button use = smallButton(profile.id().equals(active.id()) ? "当前" : "使用");
            use.setEnabled(!profile.id().equals(active.id()));
            use.setOnClickListener(v -> { store.setActiveProvider(profile.id()); showModelsSettings(); });
            top.addView(use, new LinearLayout.LayoutParams(dp(64), dp(38)));
            card.addView(top, matchWrap());
            card.addView(text(profile.model() + "\n" + profile.baseUrl(), 12, MUTED, false), matchWrap());
            card.setOnClickListener(v -> editProviderDialog(profile));
            LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(10); form.addView(card, params);
        }
        Button add = secondaryButton("＋ 添加自定义提供方");
        add.setOnClickListener(v -> editProviderDialog(null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50)); params.topMargin = dp(14);
        form.addView(add, params);
    }

    private void editProviderDialog(AppStore.ProviderProfile existing) {
        LinearLayout form = column(0); form.setPadding(dp(18), dp(4), dp(18), 0);
        EditText name = field("显示名称", existing == null ? "" : existing.name(), false);
        EditText id = field("提供方 ID", existing == null ? "custom-" + System.currentTimeMillis() : existing.id(), false);
        id.setEnabled(existing == null);
        EditText base = field("HTTPS API 地址", existing == null ? "https://" : existing.baseUrl(), false);
        EditText model = field("模型 ID", existing == null ? "" : existing.model(), false);
        EditText key = field("API Key（留空保留）", "", true);
        Spinner protocol = spinner(new String[]{"OpenAI 兼容", "Anthropic", "Google Gemini"});
        if (existing != null) protocol.setSelection(existing.protocol() == ProviderRegistry.Protocol.ANTHROPIC ? 1 :
                (existing.protocol() == ProviderRegistry.Protocol.GEMINI ? 2 : 0));
        for (View view : new View[]{name, id, base, model, protocol, key}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50)); params.topMargin = dp(7); form.addView(view, params);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "添加自定义提供方" : "编辑提供方")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (name.getText().toString().trim().isEmpty()) { name.setError("请填写名称"); return; }
            if (!base.getText().toString().trim().startsWith("https://")) { base.setError("必须使用 HTTPS"); return; }
            if (model.getText().toString().trim().isEmpty()) { model.setError("请填写模型 ID"); return; }
            ProviderRegistry.Protocol protocolId = new ProviderRegistry.Protocol[]{ProviderRegistry.Protocol.OPENAI,
                    ProviderRegistry.Protocol.ANTHROPIC, ProviderRegistry.Protocol.GEMINI}[protocol.getSelectedItemPosition()];
            AppStore.ProviderProfile profile = new AppStore.ProviderProfile(id.getText().toString().trim(),
                    name.getText().toString().trim(), base.getText().toString().trim(), model.getText().toString().trim(),
                    protocolId, java.util.Collections.singletonList(model.getText().toString().trim()), existing != null && existing.builtIn());
            store.upsertProvider(profile);
            try {
                if (!key.getText().toString().isBlank()) secrets.put(profile.id(), key.getText().toString().trim());
            } catch (Exception error) {
                Toast.makeText(this, "密钥保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); return;
            }
            if (existing == null) store.setActiveProvider(profile.id());
            dialog.dismiss(); showModelsSettings();
        }));
        dialog.show();
    }

    private void showPluginsSettings() { showPluginConfiguration(); }

    private LinearLayout pluginHeader(String active) {
        LinearLayout form = settingsPage("plugins", "插件", "配置并查看本地部署的上游兼容模块。");
        LinearLayout tabs = new LinearLayout(this);
        Button config = secondaryButton("插件配置"); Button list = secondaryButton("插件列表");
        config.setTextColor("config".equals(active) ? BLUE : MUTED); list.setTextColor("list".equals(active) ? BLUE : MUTED);
        config.setOnClickListener(v -> showPluginConfiguration()); list.setOnClickListener(v -> showPluginList(""));
        tabs.addView(config, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(0, dp(44), 1); listParams.leftMargin = dp(7);
        tabs.addView(list, listParams); form.addView(tabs, matchWrap());
        return form;
    }

    private void showPluginConfiguration() {
        LinearLayout form = pluginHeader("config");
        addPluginConfigCard(form, "终端", "限制 Agent 运行的每一条命令。",
                "超时 " + store.shellTimeoutSeconds() + " 秒 · 每流 " + store.shellOutputKb() + " KiB", () -> editShellConfig());
        addPluginConfigCard(form, "Agent 循环", "控制工具调用与执行策略。",
                "最多并行 " + store.maxParallelTools() + " 个工具", () -> editAgentLoopConfig());
        addPluginConfigCard(form, "网页搜索", "DeepSeek 搜索提供方及网页获取限制。",
                "每次最多 " + store.maxWebSearches() + " 个搜索", () -> editWebConfig());
    }

    private void addPluginConfigCard(LinearLayout form, String title, String subtitle, String value, Runnable action) {
        LinearLayout card = column(0); card.setPadding(dp(16), dp(14), dp(16), dp(14)); card.setBackground(round(CARD, 15));
        card.addView(text(title + "  ›", 17, INK, true), matchWrap());
        card.addView(text(subtitle + "\n" + value, 13, MUTED, false), matchWrap()); card.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(10); form.addView(card, params);
    }

    private void editShellConfig() {
        EditText timeout = numberField("命令超时（秒）", store.shellTimeoutSeconds());
        EditText output = numberField("每流输出上限（KiB）", store.shellOutputKb());
        showTwoNumberDialog("终端", timeout, output, () -> store.savePluginConfig(parseInt(timeout, 20), parseInt(output, 64),
                store.maxParallelTools(), store.maxWebSearches()));
    }

    private void editAgentLoopConfig() {
        EditText parallel = numberField("最大并行工具数", store.maxParallelTools());
        showOneNumberDialog("Agent 循环", parallel, () -> store.savePluginConfig(store.shellTimeoutSeconds(),
                store.shellOutputKb(), parseInt(parallel, 3), store.maxWebSearches()));
    }

    private void editWebConfig() {
        EditText searches = numberField("每次最大搜索数", store.maxWebSearches());
        showOneNumberDialog("网页搜索", searches, () -> store.savePluginConfig(store.shellTimeoutSeconds(),
                store.shellOutputKb(), store.maxParallelTools(), parseInt(searches, 5)));
    }

    private void showPluginList(String query) {
        LinearLayout form = pluginHeader("list");
        EditText search = field("搜索插件", query, false); form.addView(search, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView count = text("插件列表  " + PluginCatalog.all().size(), 15, INK, true);
        count.setPadding(0, dp(12), 0, dp(4)); form.addView(count, matchWrap());
        String needle = query.toLowerCase(Locale.ROOT).trim();
        for (PluginCatalog.Entry entry : PluginCatalog.all()) {
            if (!needle.isEmpty() && !entry.id().toLowerCase(Locale.ROOT).contains(needle)
                    && !entry.moduleName().toLowerCase(Locale.ROOT).contains(needle)) continue;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(13), dp(8), dp(8), dp(8)); row.setBackground(round(CARD, 13));
            TextView details = text(entry.id() + "\n" + entry.kind().name().toLowerCase(Locale.ROOT), 14, INK, true);
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            Switch toggle = new Switch(this); toggle.setContentDescription("启用 " + entry.id());
            toggle.setChecked(PluginCatalog.enabled(store.settings(), entry.id()));
            toggle.setOnCheckedChangeListener((button, checked) -> PluginCatalog.setEnabled(store.settings(), entry.id(), checked));
            row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(48)));
            LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(7); form.addView(row, params);
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String next = s.toString();
                if (!next.equals(query)) { showPluginList(next); }
            }
        });
        search.requestFocus(); search.setSelection(search.length());
    }

    private void showPresetsSettings() {
        LinearLayout form = settingsPage("presets", "Agent 预设", "预设决定会话可用的插件、工具、提示词与能力。");
        for (AppStore.AgentPreset preset : store.presets()) {
            LinearLayout card = column(0); card.setPadding(dp(16), dp(14), dp(16), dp(14)); card.setBackground(round(CARD, 15));
            boolean current = preset.id().equals(store.defaultPresetId());
            card.addView(text(preset.name() + (preset.builtIn() ? "  内置" : "  自定义") + (current ? "  · 当前" : ""),
                    17, current ? BLUE : INK, true), matchWrap());
            card.addView(text(preset.description() + "\n" + preset.id(), 13, MUTED, false), matchWrap());
            LinearLayout actions = new LinearLayout(this);
            Button use = smallButton(current ? "当前使用" : "设为默认"); use.setEnabled(!current);
            use.setOnClickListener(v -> { store.setDefaultPreset(preset.id()); showPresetsSettings(); });
            Button duplicate = smallButton("复制"); duplicate.setOnClickListener(v -> {
                store.addCustomPreset(preset.name() + " 副本", preset.description(), preset.baseId()); showPresetsSettings();
            });
            actions.addView(use, new LinearLayout.LayoutParams(0, dp(40), 1));
            LinearLayout.LayoutParams dup = new LinearLayout.LayoutParams(0, dp(40), 1); dup.leftMargin = dp(7); actions.addView(duplicate, dup);
            card.addView(actions, matchWrap());
            LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(10); form.addView(card, params);
        }
        Button create = secondaryButton("＋ 创建自定义预设"); create.setOnClickListener(v -> createPresetDialog());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50)); params.topMargin = dp(14); form.addView(create, params);
    }

    private void createPresetDialog() {
        LinearLayout form = column(0); form.setPadding(dp(18), 0, dp(18), 0);
        EditText name = field("预设名称", "", false); EditText description = multiline("能力与用途", "", 3);
        Spinner base = spinner(new String[]{"标准模式能力", "PTC 模式能力", "极简模式能力", "创造模式能力"});
        form.addView(name, new LinearLayout.LayoutParams(-1, dp(50)));
        LinearLayout.LayoutParams desc = new LinearLayout.LayoutParams(-1, dp(96)); desc.topMargin = dp(8); form.addView(description, desc);
        LinearLayout.LayoutParams baseParams = new LinearLayout.LayoutParams(-1, dp(52)); baseParams.topMargin = dp(8); form.addView(base, baseParams);
        new AlertDialog.Builder(this).setTitle("创建自定义预设").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String baseId = new String[]{"standard", "code", "minimal", "cordis"}[base.getSelectedItemPosition()];
                    if (!name.getText().toString().trim().isEmpty()) store.addCustomPreset(name.getText().toString().trim(), description.getText().toString().trim(), baseId);
                    showPresetsSettings();
                }).show();
    }

    private void showConfigExport() {
        try {
            String value = store.exportConfig().toString(2);
            TextView body = text(value, 12, INK, false); body.setTypeface(Typeface.MONOSPACE); body.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(this); scroll.setPadding(dp(12), dp(4), dp(12), dp(4)); scroll.addView(body);
            new AlertDialog.Builder(this).setTitle("配置文件（不含密钥）").setView(scroll)
                    .setNegativeButton("关闭", null).setPositiveButton("复制", (d, w) -> {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dsh-config.json", value));
                        Toast.makeText(this, "配置已复制", Toast.LENGTH_SHORT).show();
                    }).show();
        } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void pickAttachment() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_ATTACHMENT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ATTACHMENT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = queryName(uri);
        name = name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
        File directory = new File(store.workspace(), "attachments");
        if (!directory.exists()) directory.mkdirs();
        File destination = new File(directory, System.currentTimeMillis() + "_" + name);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IllegalStateException("无法读取文件");
            byte[] buffer = new byte[8192]; int read; long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 10L * 1024 * 1024) throw new IllegalArgumentException("附件不能超过 10 MiB");
                output.write(buffer, 0, read);
            }
            pendingAttachment = "attachments/" + destination.getName();
            Toast.makeText(this, "已导入 " + pendingAttachment, Toast.LENGTH_LONG).show();
            if ("workspace".equals(screen)) showWorkspace();
            else if (chatInput != null) chatInput.setHint("已附加 " + name);
        } catch (Exception error) {
            destination.delete();
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        String segment = uri.getLastPathSegment();
        return segment == null ? "attachment" : segment;
    }

    private void promptNewTask() {
        EditText input = field("任务步骤", "", false);
        new AlertDialog.Builder(this).setTitle("添加任务").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("添加", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isBlank()) store.addPlan(activeSession, value);
                    showPlans();
                }).show();
    }

    private void promptNewGoal() {
        EditText input = field("要达成的目标", "", false);
        new AlertDialog.Builder(this).setTitle("新建目标").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("建立", (d, w) -> {
                    String value = input.getText().toString().trim(); if (!value.isEmpty()) store.addGoal(activeSession, value); showGoals();
                }).show();
    }

    private void promptNewJob() {
        EditText input = multiline("例如：find . -type f", "", 3);
        new AlertDialog.Builder(this).setTitle("启动后台 Job").setMessage("命令仅在应用私有工作区运行，输出写入 .dsh-job-<id>.log。")
                .setView(input).setNegativeButton("取消", null).setPositiveButton("启动", (d, w) -> {
                    try {
                        String result = new MobileTools(store.workspace(), store, activeSession).execute("start_job",
                                new org.json.JSONObject().put("command", input.getText().toString()));
                        Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                    } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
                    showJobs();
                }).show();
    }

    private void promptNewWorkflow() {
        LinearLayout form = column(0); form.setPadding(dp(18), 0, dp(18), 0);
        EditText name = field("工作流名称", "", false); EditText prompt = multiline("给 Agent 的完整执行指令", "", 4);
        form.addView(name, new LinearLayout.LayoutParams(-1, dp(50)));
        LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(-1, dp(116)); promptParams.topMargin = dp(8); form.addView(prompt, promptParams);
        new AlertDialog.Builder(this).setTitle("创建工作流").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String title = name.getText().toString().trim(); String task = prompt.getText().toString().trim();
                    if (!title.isEmpty() && !task.isEmpty()) store.addWorkflow(activeSession, title, task); showWorkflows();
                }).show();
    }

    private void confirmDelete(AppStore.Session session) {
        new AlertDialog.Builder(this).setTitle("删除会话？").setMessage(session.title)
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                    store.deleteSession(session.id);
                    activeSession = store.sessions().isEmpty() ? store.createSession() : store.sessions().get(0);
                    showSessions();
                }).show();
    }

    private String providerSummary() {
        AppStore.ProviderProfile provider = store.activeProviderProfile();
        return provider.name() + "  ·  " + store.model() + (secrets.has(provider.id()) ? "  ·  密钥已保存" : "  ·  请先设置密钥");
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        return spinner;
    }

    private EditText multiline(String hint, String value, int minLines) {
        EditText field = new EditText(this);
        field.setHint(hint); field.setText(value); field.setTextSize(15); field.setTextColor(INK);
        field.setMinLines(minLines); field.setMaxLines(Math.max(minLines, 7));
        field.setGravity(Gravity.TOP | Gravity.START); field.setPadding(dp(13), dp(10), dp(13), dp(10));
        field.setHintTextColor(MUTED); field.setBackground(round(CARD, 12));
        return field;
    }

    private Button primaryButton(String value) {
        Button button = smallButton(value); button.setTextColor(Color.WHITE); button.setTextSize(16);
        button.setTypeface(null, Typeface.BOLD); button.setBackground(round(BLUE, 16)); return button;
    }

    private Button secondaryButton(String value) {
        Button button = smallButton(value); button.setTextColor(INK); button.setBackground(round(CARD, 14)); return button;
    }

    private EditText numberField(String hint, int value) {
        EditText field = field(hint, String.valueOf(value), false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER); return field;
    }

    private int parseInt(EditText field, int fallback) {
        try { return Integer.parseInt(field.getText().toString()); } catch (Exception ignored) { return fallback; }
    }

    private void showOneNumberDialog(String title, EditText field, Runnable save) {
        LinearLayout form = column(0); form.setPadding(dp(18), 0, dp(18), 0); form.addView(field, new LinearLayout.LayoutParams(-1, dp(52)));
        new AlertDialog.Builder(this).setTitle(title).setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> { save.run(); showPluginConfiguration(); }).show();
    }

    private void showTwoNumberDialog(String title, EditText first, EditText second, Runnable save) {
        LinearLayout form = column(0); form.setPadding(dp(18), 0, dp(18), 0);
        form.addView(first, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52)); params.topMargin = dp(8); form.addView(second, params);
        new AlertDialog.Builder(this).setTitle(title).setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> { save.run(); showPluginConfiguration(); }).show();
    }

    private TextView fileRow(String value, boolean bold) {
        TextView row = text(value, 15, INK, bold);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(round(CARD, 13));
        return row;
    }

    private TextView label(String value) {
        TextView label = text(value, 13, MUTED, true);
        label.setPadding(0, dp(9), 0, dp(3));
        return label;
    }

    private EditText field(String hint, String value, boolean password) {
        EditText field = new EditText(this);
        field.setHint(hint); field.setText(value); field.setTextSize(15); field.setTextColor(INK);
        field.setSingleLine(true); field.setPadding(dp(13), dp(9), dp(13), dp(9));
        field.setHintTextColor(MUTED); field.setBackground(round(CARD, 12));
        if (password) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        return field;
    }

    private Button smallButton(String value) {
        Button button = new Button(this);
        button.setText(value); button.setTextSize(14); button.setTextColor(BLUE);
        button.setAllCaps(false); button.setPadding(0, 0, 0, 0);
        button.setBackground(round(Color.rgb(238, 241, 249), 15));
        return button;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private LinearLayout column(int spacingIgnored) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private FrameLayout.LayoutParams matchParent() { return new FrameLayout.LayoutParams(-1, -1); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
