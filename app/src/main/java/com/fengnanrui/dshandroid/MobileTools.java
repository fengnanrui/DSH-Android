package com.fengnanrui.dshandroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/** Sandboxed native tools exposed according to the active preset and plugin inventory. */
public final class MobileTools {
    public static final int MAX_FILE_BYTES = 256 * 1024;
    private final File workspace;
    private final AppStore store;
    private final AppStore.Session session;
    private int webRequests;
    private volatile ManagedShell foregroundProcess;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean cancelled;

    public MobileTools(File workspace, AppStore store, AppStore.Session session) {
        this.workspace = workspace; this.store = store; this.session = session;
    }

    public static JSONArray definitions() throws Exception { return definitions(null, null); }

    public static JSONArray definitions(AppStore store, AppStore.Session session) throws Exception {
        JSONArray tools = new JSONArray();
        add(tools, store, session, "tool-fs", tool("list_files", "列出工作区目录", props(
                prop("path", "string", "相对目录，默认为 .")), new JSONArray()));
        add(tools, store, session, "tool-fs", tool("read_file", "读取工作区内的 UTF-8 文本文件", props(
                prop("path", "string", "相对文件路径")), new JSONArray().put("path")));
        add(tools, store, session, "tool-fs", tool("write_file", "创建或覆盖工作区内的文本文件", props(
                prop("path", "string", "相对文件路径"), prop("content", "string", "完整文件内容")),
                new JSONArray().put("path").put("content")));
        add(tools, store, session, "tool-str-replace-editor", tool("str_replace_editor", "精确替换文件中的唯一文本", props(
                prop("path", "string", "相对文件路径"), prop("old", "string", "必须唯一的原文"),
                prop("replacement", "string", "替换文本")), new JSONArray().put("path").put("old").put("replacement")));
        add(tools, store, session, "tool-fs-search", tool("search_files", "在工作区文本文件中搜索内容", props(
                prop("query", "string", "搜索文本"), prop("path", "string", "相对目录")), new JSONArray().put("query")));
        add(tools, store, session, "tool-fs-search", tool("glob_files", "按名称片段或扩展名查找文件", props(
                prop("pattern", "string", "例如 .java、README 或 *")), new JSONArray().put("pattern")));
        add(tools, store, session, "tool-bash", tool("run_shell", "在应用私有工作区运行 Android shell 命令", props(
                prop("command", "string", "Shell 命令")), new JSONArray().put("command")));
        add(tools, store, session, "tool-jobs", tool("start_job", "在后台启动 Shell 任务", props(
                prop("command", "string", "后台命令")), new JSONArray().put("command")));
        add(tools, store, session, "tool-jobs", tool("list_jobs", "列出本应用启动的后台任务", new JSONObject(), new JSONArray()));
        add(tools, store, session, "tool-jobs", tool("stop_job", "停止后台任务", props(
                prop("id", "integer", "任务编号")), new JSONArray().put("id")));
        add(tools, store, session, "tool-web", tool("fetch_url", "获取 HTTPS 网页文本", props(
                prop("url", "string", "HTTPS URL")), new JSONArray().put("url")));
        add(tools, store, session, "timer", tool("current_time", "读取手机当前日期、时间和时区", new JSONObject(), new JSONArray()));
        add(tools, store, session, "tool-todo", tool("update_plan", "把待办步骤加入当前会话任务列表", props(
                prop("step", "string", "步骤说明")), new JSONArray().put("step")));
        add(tools, store, session, "tool-goal", tool("create_goal", "为当前会话建立持久目标", props(
                prop("goal", "string", "目标说明")), new JSONArray().put("goal")));
        add(tools, store, session, "tool-workflow", tool("save_workflow", "保存可重复运行的 Agent 工作流提示", props(
                prop("name", "string", "工作流名称"), prop("prompt", "string", "执行指令")),
                new JSONArray().put("name").put("prompt")));
        add(tools, store, session, "tool-skill", tool("list_skills", "列出 .dsh/skills 中的本地技能", new JSONObject(), new JSONArray()));
        add(tools, store, session, "tool-skill", tool("read_skill", "读取本地技能的 SKILL.md", props(
                prop("name", "string", "技能目录名")), new JSONArray().put("name")));
        add(tools, store, session, "tool-subagent", tool("delegate_task", "启动独立子 Agent 分析子任务", props(
                prop("task", "string", "具体子任务"), prop("context", "string", "所需背景")), new JSONArray().put("task")));
        add(tools, store, session, "tool-ask-user", tool("ask_user", "需要用户决定或补充信息时显示原生提问框", props(
                prop("question", "string", "要向用户提出的问题")), new JSONArray().put("question")));
        add(tools, store, session, "code-runtime", tool("run_code_mode", "按顺序运行最多 8 个原生工具步骤；steps 是 JSON 数组字符串", props(
                prop("steps", "string", "例如 [{\"tool\":\"read_file\",\"args\":{\"path\":\"README.md\"}}]")),
                new JSONArray().put("steps")));
        add(tools, store, session, "plugin-inventory", tool("list_plugins", "列出原生插件与仅兼容标识", new JSONObject(), new JSONArray()));
        add(tools, store, session, "settings", tool("export_config", "导出当前非敏感 DSH 配置", new JSONObject(), new JSONArray()));
        add(tools, store, session, "agent-presets", tool("create_agent_preset", "创建真正绑定某一能力组的自定义预设", props(
                prop("name", "string", "预设名称"), prop("description", "string", "用途说明"),
                prop("base", "string", "standard、code、minimal 或 cordis")),
                new JSONArray().put("name").put("base")));
        return tools;
    }

    public static boolean requiresApproval(String name) {
        return "write_file".equals(name) || "str_replace_editor".equals(name) || "run_shell".equals(name)
                || "start_job".equals(name) || "stop_job".equals(name) || "fetch_url".equals(name)
                || "run_code_mode".equals(name) || "create_agent_preset".equals(name);
    }

    public String execute(String name, JSONObject args) throws Exception {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!isToolAvailable(name)) throw new SecurityException("当前预设或插件未提供工具：" + name);
        return switch (name) {
            case "list_files" -> listFiles(args.optString("path", "."));
            case "read_file" -> readFile(args.getString("path"));
            case "write_file" -> writeFile(args.getString("path"), args.getString("content"));
            case "str_replace_editor" -> replace(args.getString("path"), args.getString("old"), args.getString("replacement"));
            case "search_files" -> search(args.getString("query"), args.optString("path", "."));
            case "glob_files" -> glob(args.getString("pattern"));
            case "run_shell" -> runShell(args.getString("command"));
            case "start_job" -> startJob(args.getString("command"));
            case "list_jobs" -> listJobs();
            case "stop_job" -> stopJob(args.getInt("id"));
            case "fetch_url" -> fetch(args.getString("url"));
            case "current_time" -> ZonedDateTime.now().toString();
            case "update_plan" -> updatePlan(args.getString("step"));
            case "create_goal" -> createGoal(args.getString("goal"));
            case "save_workflow" -> saveWorkflow(args.getString("name"), args.getString("prompt"));
            case "list_skills" -> listSkills();
            case "read_skill" -> readSkill(args.getString("name"));
            case "list_plugins" -> listPlugins();
            case "export_config" -> store.exportConfig().toString(2);
            case "run_code_mode" -> runCodeMode(args.getString("steps"));
            case "create_agent_preset" -> createAgentPreset(args);
            default -> throw new IllegalArgumentException("未知工具: " + name);
        };
    }

    /** Cancel the foreground operation without stopping separately managed background jobs. */
    public void cancel() {
        cancelled = true;
        ManagedShell process = foregroundProcess;
        if (process != null) process.close();
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    public File resolve(String relative) throws Exception {
        String clean = relative == null || relative.trim().isEmpty() ? "." : relative;
        File root = workspace.getCanonicalFile();
        File target = new File(root, clean).getCanonicalFile();
        if (!target.equals(root) && !target.getPath().startsWith(root.getPath() + File.separator))
            throw new SecurityException("路径越过工作区边界");
        return target;
    }

    private String listFiles(String path) throws Exception {
        File directory = resolve(path);
        if (!directory.isDirectory()) throw new IllegalArgumentException("不是目录: " + path);
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) return "（空目录）";
        StringBuilder output = new StringBuilder();
        for (File file : files) {
            output.append(file.isDirectory() ? "[目录] " : "[文件] ")
                    .append(workspace.toPath().relativize(file.toPath()));
            if (file.isFile()) output.append("  ").append(file.length()).append(" bytes");
            output.append('\n');
        }
        return output.toString().trim();
    }

    private String readFile(String path) throws Exception {
        File file = resolve(path);
        if (!file.isFile()) throw new IllegalArgumentException("文件不存在: " + path);
        if (file.length() > MAX_FILE_BYTES) throw new IllegalArgumentException("文件超过 256 KiB 限制");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private String writeFile(String path, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("写入内容超过 256 KiB 限制");
        File file = resolve(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建目录");
        Files.write(file.toPath(), bytes);
        return "已写入 " + workspace.toPath().relativize(file.toPath()) + "（" + bytes.length + " bytes）";
    }

    private String replace(String path, String old, String replacement) throws Exception {
        String value = readFile(path);
        int first = value.indexOf(old);
        if (first < 0) throw new IllegalArgumentException("原文未找到");
        if (value.indexOf(old, first + old.length()) >= 0) throw new IllegalArgumentException("原文出现多次，请提供更完整上下文");
        return writeFile(path, value.substring(0, first) + replacement + value.substring(first + old.length()));
    }

    private String search(String query, String path) throws Exception {
        if (query.trim().isEmpty()) throw new IllegalArgumentException("搜索内容为空");
        List<String> hits = new ArrayList<>();
        searchRecursive(resolve(path), query.toLowerCase(Locale.ROOT), hits, new HashSet<>());
        return hits.isEmpty() ? "没有匹配结果" : String.join("\n", hits.subList(0, Math.min(100, hits.size())));
    }

    private void searchRecursive(File file, String query, List<String> hits, Set<String> visited) throws Exception {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (hits.size() >= 100 || Files.isSymbolicLink(file.toPath()) || !visited.add(file.getCanonicalPath())) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) searchRecursive(child, query, hits, visited);
        } else if (file.isFile() && file.length() <= MAX_FILE_BYTES) {
            List<String> lines;
            try { lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8); } catch (Exception ignored) { return; }
            for (int i = 0; i < lines.size() && hits.size() < 100; i++) if (lines.get(i).toLowerCase(Locale.ROOT).contains(query))
                hits.add(workspace.toPath().relativize(file.toPath()) + ":" + (i + 1) + ": " + lines.get(i).trim());
        }
    }

    private String glob(String pattern) throws Exception {
        List<String> hits = new ArrayList<>();
        globRecursive(workspace, pattern.toLowerCase(Locale.ROOT).replace("*", ""), hits, new HashSet<>());
        return hits.isEmpty() ? "没有匹配文件" : String.join("\n", hits.subList(0, Math.min(200, hits.size())));
    }

    private void globRecursive(File file, String needle, List<String> hits, Set<String> visited) throws Exception {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (hits.size() >= 200 || Files.isSymbolicLink(file.toPath()) || !visited.add(file.getCanonicalPath())) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) globRecursive(child, needle, hits, visited);
        } else if (needle.isEmpty() || file.getName().toLowerCase(Locale.ROOT).contains(needle))
            hits.add(workspace.toPath().relativize(file.toPath()).toString());
    }

    private String runShell(String command) throws Exception {
        if (command.trim().isEmpty() || command.length() > 4000) throw new IllegalArgumentException("命令为空或过长");
        ManagedShell shell = ManagedShell.start(workspace, command, store.shellTimeoutSeconds());
        Process process = shell.process;
        foregroundProcess = shell;
        AtomicReference<String> output = new AtomicReference<>("");
        AtomicReference<Exception> readError = new AtomicReference<>();
        int limit = store.shellOutputKb() * 1024;
        Thread reader = new Thread(() -> {
            try (InputStreamReader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                output.set(BoundedText.read(input, limit, true));
            } catch (Exception error) { readError.set(error); }
        }, "dsh-shell-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (cancelled) throw new InterruptedException();
            if (!process.waitFor(store.shellTimeoutSeconds(), TimeUnit.SECONDS))
                throw new IllegalStateException("命令运行超过 " + store.shellTimeoutSeconds() + " 秒，已终止");
            reader.join(1000);
            if (reader.isAlive()) throw new IllegalStateException("命令已结束，但输出管道仍被子进程占用");
            if (readError.get() != null) throw readError.get();
            return "exit=" + process.exitValue() + "\n" + output.get().trim();
        } finally {
            foregroundProcess = null;
            shell.close();
            reader.interrupt();
            process.getInputStream().close();
        }
    }

    private String startJob(String command) throws Exception {
        return store.jobs().start(command);
    }

    private String listJobs() { return store.jobs().list(); }

    private String stopJob(int id) { return store.jobs().stop(id); }

    private String fetch(String rawUrl) throws Exception {
        if (++webRequests > store.maxWebRequests())
            throw new IllegalStateException("本轮网页访问超过设置上限 " + store.maxWebRequests());
        URI uri = URI.create(rawUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new SecurityException("只允许 HTTPS URL");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        activeConnection = connection;
        connection.setConnectTimeout(15_000); connection.setReadTimeout(25_000);
        connection.setRequestProperty("User-Agent", "DSH-Android/" + BuildConfig.VERSION_NAME);
        connection.setInstanceFollowRedirects(false);
        try {
            if (cancelled) throw new InterruptedException();
            int status = connection.getResponseCode();
            java.io.InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return "HTTP " + status;
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return "HTTP " + status + "\n" + BoundedText.read(reader, MAX_FILE_BYTES, false).trim();
            }
        } finally { activeConnection = null; connection.disconnect(); }
    }

    private String updatePlan(String step) { store.addPlan(session, step); return "已加入任务列表: " + step; }
    private String createGoal(String goal) { store.addGoal(session, goal); return "已建立目标: " + goal; }
    private String saveWorkflow(String name, String prompt) {
        store.addWorkflow(session, name, prompt); return "已保存工作流: " + name;
    }

    private String listSkills() throws Exception {
        File skills = resolve(".dsh/skills");
        if (!skills.isDirectory()) return "尚无本地技能。可在 .dsh/skills/<name>/SKILL.md 创建。";
        StringBuilder result = new StringBuilder();
        File[] children = skills.listFiles();
        if (children != null) for (File child : children) if (new File(child, "SKILL.md").isFile()) result.append(child.getName()).append('\n');
        return result.length() == 0 ? "尚无有效技能" : result.toString().trim();
    }

    private String readSkill(String name) throws Exception {
        if (!name.matches("[A-Za-z0-9._-]+")) throw new SecurityException("技能名称无效");
        return readFile(".dsh/skills/" + name + "/SKILL.md");
    }

    private String listPlugins() {
        StringBuilder value = new StringBuilder();
        for (PluginCatalog.Entry entry : PluginCatalog.all()) {
            String status = entry.availability() == PluginCatalog.Availability.COMPATIBILITY_ONLY
                    ? "[仅兼容标识] "
                    : (PluginCatalog.enabled(store.settings(), entry.id()) ? "[原生已启用] " : "[原生已停用] ");
            value.append(status).append(entry.id()).append(" · ").append(entry.kind()).append('\n');
        }
        return value.toString().trim();
    }

    private String runCodeMode(String rawSteps) throws Exception {
        JSONArray steps = new JSONArray(rawSteps);
        if (steps.length() == 0 || steps.length() > 8) throw new IllegalArgumentException("Code Mode 需要 1–8 个步骤");
        JSONArray results = new JSONArray();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String toolName = step.getString("tool");
            if ("run_code_mode".equals(toolName) || "delegate_task".equals(toolName)
                    || "ask_user".equals(toolName)) throw new SecurityException("Code Mode 不允许嵌套 " + toolName);
            if (!isToolAvailable(toolName)) throw new SecurityException("当前预设或插件未提供工具：" + toolName);
            JSONObject arguments = step.optJSONObject("args");
            if (arguments == null) arguments = new JSONObject();
            String result = execute(toolName, arguments);
            results.put(new JSONObject().put("step", i + 1).put("tool", toolName).put("result", result));
        }
        return results.toString(2);
    }

    private boolean isToolAvailable(String name) throws Exception {
        return isToolAvailable(definitions(store, session), name);
    }

    static boolean isToolAvailable(JSONArray available, String name) throws Exception {
        for (int i = 0; i < available.length(); i++) {
            if (name.equals(available.getJSONObject(i).getJSONObject("function").optString("name"))) return true;
        }
        return false;
    }

    private String createAgentPreset(JSONObject args) {
        String base = args.optString("base", "standard");
        String name = args.optString("name", "").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("预设名称不能为空");
        if (!("standard".equals(base) || "code".equals(base) || "minimal".equals(base) || "cordis".equals(base)))
            throw new IllegalArgumentException("base 必须是 standard、code、minimal 或 cordis");
        store.addCustomPreset(name, args.optString("description"), base);
        return "已创建自定义预设：" + name + "（" + base + "）";
    }

    private static void add(JSONArray tools, AppStore store, AppStore.Session session, String plugin, JSONObject definition) {
        if (store == null || session == null) { tools.put(definition); return; }
        if (!shouldInclude(store.preset(session.presetId).baseId(), plugin)) return;
        if (PluginCatalog.enabled(store.settings(), plugin)) tools.put(definition);
    }

    static boolean shouldInclude(String baseId, String plugin) {
        if ("minimal".equals(baseId))
            return "tool-bash".equals(plugin) || "tool-str-replace-editor".equals(plugin);
        if ("code-runtime".equals(plugin)) return "code".equals(baseId);
        if ("plugin-inventory".equals(plugin) || "settings".equals(plugin) || "agent-presets".equals(plugin))
            return "cordis".equals(baseId);
        return true;
    }

    private static JSONObject prop(String name, String type, String description) throws Exception {
        return new JSONObject().put("_name", name).put("type", type).put("description", description);
    }

    private static JSONObject props(JSONObject... values) throws Exception {
        JSONObject result = new JSONObject();
        for (JSONObject value : values) result.put(value.getString("_name"), new JSONObject()
                .put("type", value.getString("type")).put("description", value.getString("description")));
        return result;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, JSONArray required) throws Exception {
        return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name)
                .put("description", description).put("parameters", new JSONObject().put("type", "object")
                        .put("properties", properties).put("required", required)));
    }
}
