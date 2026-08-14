package com.fengnanrui.dshandroid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Transparent local state for sessions and the mobile DSH configuration. */
public final class AppStore {
    public static final class Message {
        public String role;
        public String content;
        public String toolName;
        public long time;

        public Message(String role, String content) { this(role, content, "", System.currentTimeMillis()); }
        public Message(String role, String content, String toolName, long time) {
            this.role = role; this.content = content; this.toolName = toolName; this.time = time;
        }
    }

    public static final class PlanItem {
        public String id = UUID.randomUUID().toString();
        public String text;
        public boolean done;
        public PlanItem(String text) { this.text = text; }
    }

    public static final class GoalItem {
        public String id = UUID.randomUUID().toString();
        public String text;
        public boolean achieved;
        public GoalItem(String text) { this.text = text; }
    }

    public static final class WorkflowItem {
        public String id = UUID.randomUUID().toString();
        public String name;
        public String prompt;
        public WorkflowItem(String name, String prompt) { this.name = name; this.prompt = prompt; }
    }

    public static final class Session {
        public String id = UUID.randomUUID().toString();
        public String title = "新会话";
        public String presetId = "standard";
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = createdAt;
        public final List<Message> messages = new ArrayList<>();
        public final List<PlanItem> plan = new ArrayList<>();
        public final List<GoalItem> goals = new ArrayList<>();
        public final List<WorkflowItem> workflows = new ArrayList<>();
    }

    public record ProviderProfile(String id, String name, String baseUrl, String model,
                                  ProviderRegistry.Protocol protocol, List<String> models,
                                  boolean builtIn) {
        @Override public String toString() { return name; }
        public ProviderRegistry.Provider runtimeProvider() {
            return new ProviderRegistry.Provider(id, name, baseUrl, model, protocol);
        }
    }

    public record AgentPreset(String id, String name, String description, boolean builtIn, String baseId) {}

    private static final String PREFS = "dsh_settings";
    private static final List<AgentPreset> BUILT_IN_PRESETS = Arrays.asList(
            new AgentPreset("standard", "标准模式", "完整编码 Agent：文件、Shell、搜索、Skills、计划、目标、子代理和工作流。", true, "standard"),
            new AgentPreset("code", "PTC 模式", "标准模式全部能力，并提供 Code Mode 多步骤工具组合。", true, "code"),
            new AgentPreset("minimal", "极简模式", "仅提供持久 Shell 与 str_replace_editor。", true, "minimal"),
            new AgentPreset("cordis", "创造模式", "标准能力加运行时检查、插件实验与预设创建指导。", true, "cordis"));

    private final File sessionsFile;
    private final File workspace;
    private final SharedPreferences settings;
    private final List<Session> sessions = new ArrayList<>();

    public AppStore(Context context) {
        File stateDir = new File(context.getFilesDir(), "state");
        if (!stateDir.exists()) stateDir.mkdirs();
        sessionsFile = new File(stateDir, "sessions.json");
        workspace = new File(context.getFilesDir(), "workspaces/default");
        if (!workspace.exists()) workspace.mkdirs();
        settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacyProvider();
        load();
    }

    public synchronized List<Session> sessions() { return Collections.unmodifiableList(new ArrayList<>(sessions)); }

    public synchronized Session session(String id) {
        for (Session session : sessions) if (session.id.equals(id)) return session;
        return sessions.isEmpty() ? createSession() : sessions.get(0);
    }

    public synchronized Session createSession() {
        Session session = new Session();
        session.presetId = defaultPresetId();
        sessions.add(0, session);
        save();
        return session;
    }

    public synchronized void deleteSession(String id) { sessions.removeIf(s -> s.id.equals(id)); save(); }

    public synchronized void addMessage(Session session, Message message) {
        session.messages.add(message);
        session.updatedAt = System.currentTimeMillis();
        if ("新会话".equals(session.title) && "user".equals(message.role)) {
            String clean = message.content.replace('\n', ' ').trim();
            session.title = clean.length() > 26 ? clean.substring(0, 26) + "…" : clean;
        }
        save();
    }

    public synchronized void addPlan(Session session, String text) {
        session.plan.add(new PlanItem(text)); session.updatedAt = System.currentTimeMillis(); save();
    }

    public synchronized void togglePlan(Session session, String id) {
        for (PlanItem item : session.plan) if (item.id.equals(id)) item.done = !item.done;
        save();
    }

    public synchronized void addGoal(Session session, String text) {
        session.goals.add(new GoalItem(text)); session.updatedAt = System.currentTimeMillis(); save();
    }

    public synchronized void toggleGoal(Session session, String id) {
        for (GoalItem item : session.goals) if (item.id.equals(id)) item.achieved = !item.achieved;
        save();
    }

    public synchronized void addWorkflow(Session session, String name, String prompt) {
        session.workflows.add(new WorkflowItem(name, prompt)); session.updatedAt = System.currentTimeMillis(); save();
    }

    public synchronized void save() {
        JSONArray root = new JSONArray();
        try {
            for (Session session : sessions) root.put(toJson(session));
            File temporary = new File(sessionsFile.getParentFile(), "sessions.tmp");
            Files.write(temporary.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
            if (!temporary.renameTo(sessionsFile)) {
                Files.write(sessionsFile.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
                temporary.delete();
            }
        } catch (Exception ignored) {}
    }

    public File workspace() { return workspace; }
    public SharedPreferences settings() { return settings; }
    public String permissionMode() { return settings.getString("permission_mode", "ask"); }
    public String agentInstructions() { return settings.getString("agent_instructions", ""); }
    public String language() { return settings.getString("language", "zh-CN"); }
    public String theme() { return settings.getString("theme", "system"); }
    public String enterBehavior() { return settings.getString("enter_behavior", "send"); }
    public int shellTimeoutSeconds() { return settings.getInt("shell_timeout_seconds", 20); }
    public int shellOutputKb() { return settings.getInt("shell_output_kb", 64); }
    public int maxParallelTools() { return settings.getInt("max_parallel_tools", 3); }
    public int maxWebSearches() { return settings.getInt("max_web_searches", 5); }
    public String defaultPresetId() { return settings.getString("default_preset", "standard"); }

    public void saveGeneral(String permission, String language, String theme, String enterBehavior) {
        settings.edit().putString("permission_mode", permission).putString("language", language)
                .putString("theme", theme).putString("enter_behavior", enterBehavior).apply();
    }

    public void savePluginConfig(int timeout, int outputKb, int parallel, int searches) {
        settings.edit().putInt("shell_timeout_seconds", Math.max(1, Math.min(300, timeout)))
                .putInt("shell_output_kb", Math.max(8, Math.min(1024, outputKb)))
                .putInt("max_parallel_tools", Math.max(1, Math.min(16, parallel)))
                .putInt("max_web_searches", Math.max(1, Math.min(20, searches))).apply();
    }

    public void saveAgentInstructions(String value) {
        settings.edit().putString("agent_instructions", value.trim()).apply();
    }

    public List<ProviderProfile> providerProfiles() {
        List<ProviderProfile> profiles = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(settings.getString("providers_json", "[]"));
            for (int i = 0; i < array.length(); i++) profiles.add(providerFromJson(array.getJSONObject(i)));
        } catch (Exception ignored) {}
        if (profiles.isEmpty()) {
            for (ProviderRegistry.Provider p : ProviderRegistry.all()) {
                if (!"custom".equals(p.id())) profiles.add(new ProviderProfile(p.id(), p.name(), p.baseUrl(),
                        p.model(), p.protocol(), new ArrayList<>(Collections.singletonList(p.model())), true));
            }
            saveProviders(profiles);
        }
        return profiles;
    }

    public ProviderProfile activeProviderProfile() {
        List<ProviderProfile> profiles = providerProfiles();
        String active = settings.getString("active_provider", settings.getString("provider", "deepseek"));
        for (ProviderProfile profile : profiles) if (profile.id().equals(active)) return profile;
        return profiles.get(0);
    }

    public void setActiveProvider(String id) { settings.edit().putString("active_provider", id).apply(); }

    public void upsertProvider(ProviderProfile profile) {
        List<ProviderProfile> profiles = providerProfiles();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id().equals(profile.id())) {
            profiles.set(i, profile); replaced = true; break;
        }
        if (!replaced) profiles.add(profile);
        saveProviders(profiles);
    }

    public void removeProvider(String id) {
        List<ProviderProfile> profiles = providerProfiles();
        profiles.removeIf(p -> p.id().equals(id) && !p.builtIn());
        saveProviders(profiles);
        if (id.equals(activeProviderProfile().id())) setActiveProvider(profiles.get(0).id());
    }

    public String providerId() { return activeProviderProfile().id(); }
    public String baseUrl() { return activeProviderProfile().baseUrl(); }
    public String model() { return activeProviderProfile().model(); }

    /** Compatibility entry point retained for existing callers and imported configuration. */
    public void saveModelSettings(String provider, String baseUrl, String model, String permissionMode) {
        ProviderProfile current = null;
        for (ProviderProfile profile : providerProfiles()) if (profile.id().equals(provider)) current = profile;
        ProviderRegistry.Provider fallback = ProviderRegistry.byId(provider);
        ProviderProfile updated = new ProviderProfile(provider, current == null ? fallback.name() : current.name(),
                baseUrl.trim(), model.trim(), current == null ? fallback.protocol() : current.protocol(),
                current == null ? Collections.singletonList(model.trim()) : current.models(), current != null && current.builtIn());
        upsertProvider(updated);
        setActiveProvider(provider);
        settings.edit().putString("permission_mode", permissionMode).apply();
    }

    public List<AgentPreset> presets() {
        List<AgentPreset> result = new ArrayList<>(BUILT_IN_PRESETS);
        try {
            JSONArray custom = new JSONArray(settings.getString("custom_presets_json", "[]"));
            for (int i = 0; i < custom.length(); i++) {
                JSONObject item = custom.getJSONObject(i);
                result.add(new AgentPreset(item.getString("id"), item.getString("name"),
                        item.optString("description"), false, item.optString("baseId", "standard")));
            }
        } catch (Exception ignored) {}
        return result;
    }

    public AgentPreset preset(String id) {
        for (AgentPreset preset : presets()) if (preset.id().equals(id)) return preset;
        return BUILT_IN_PRESETS.get(0);
    }

    public void setDefaultPreset(String id) { settings.edit().putString("default_preset", id).apply(); }

    public void addCustomPreset(String name, String description) { addCustomPreset(name, description, "standard"); }

    public void addCustomPreset(String name, String description, String baseId) {
        try {
            JSONArray custom = new JSONArray(settings.getString("custom_presets_json", "[]"));
            custom.put(new JSONObject().put("id", "custom-" + UUID.randomUUID()).put("name", name)
                    .put("description", description).put("baseId", baseId));
            settings.edit().putString("custom_presets_json", custom.toString()).apply();
        } catch (Exception ignored) {}
    }

    public JSONObject exportConfig() throws Exception {
        JSONArray providers = new JSONArray();
        for (ProviderProfile p : providerProfiles()) providers.put(providerToJson(p));
        JSONArray disabled = new JSONArray();
        for (String id : settings.getStringSet("disabled_plugins", Collections.emptySet())) disabled.put(id);
        return new JSONObject().put("schema", "dsh-android/v1").put("activeProvider", providerId())
                .put("providers", providers).put("defaultPreset", defaultPresetId())
                .put("permission", permissionMode()).put("language", language()).put("theme", theme())
                .put("enterBehavior", enterBehavior()).put("disabledPlugins", disabled)
                .put("shellTimeoutSeconds", shellTimeoutSeconds()).put("shellOutputKb", shellOutputKb())
                .put("maxParallelTools", maxParallelTools()).put("maxWebSearches", maxWebSearches());
    }

    private void migrateLegacyProvider() {
        if (settings.contains("providers_json")) return;
        String id = settings.getString("provider", "deepseek");
        ProviderRegistry.Provider p = ProviderRegistry.byId(id);
        String base = settings.getString("base_url", p.baseUrl());
        String model = settings.getString("model", p.model());
        settings.edit().putString("active_provider", id).apply();
        List<ProviderProfile> initial = new ArrayList<>();
        for (ProviderRegistry.Provider item : ProviderRegistry.all()) if (!"custom".equals(item.id())) {
            initial.add(new ProviderProfile(item.id(), item.name(), item.id().equals(id) ? base : item.baseUrl(),
                    item.id().equals(id) ? model : item.model(), item.protocol(),
                    Collections.singletonList(item.id().equals(id) ? model : item.model()), true));
        }
        saveProviders(initial);
    }

    private void saveProviders(List<ProviderProfile> profiles) {
        JSONArray array = new JSONArray();
        try { for (ProviderProfile p : profiles) array.put(providerToJson(p)); }
        catch (Exception ignored) {}
        settings.edit().putString("providers_json", array.toString()).apply();
    }

    private static JSONObject providerToJson(ProviderProfile p) throws Exception {
        return new JSONObject().put("id", p.id()).put("name", p.name()).put("baseUrl", p.baseUrl())
                .put("model", p.model()).put("protocol", p.protocol().name())
                .put("models", new JSONArray(p.models())).put("builtIn", p.builtIn());
    }

    private static ProviderProfile providerFromJson(JSONObject item) throws Exception {
        List<String> models = new ArrayList<>();
        JSONArray array = item.optJSONArray("models");
        if (array != null) for (int i = 0; i < array.length(); i++) models.add(array.getString(i));
        String model = item.optString("model", "model-name");
        if (models.isEmpty()) models.add(model);
        return new ProviderProfile(item.getString("id"), item.optString("name", item.getString("id")),
                item.optString("baseUrl"), model,
                ProviderRegistry.Protocol.valueOf(item.optString("protocol", "OPENAI")),
                models, item.optBoolean("builtIn"));
    }

    private void load() {
        try {
            if (sessionsFile.isFile()) {
                JSONArray root = new JSONArray(new String(Files.readAllBytes(sessionsFile.toPath()), StandardCharsets.UTF_8));
                for (int i = 0; i < root.length(); i++) sessions.add(fromJson(root.getJSONObject(i)));
            }
        } catch (Exception ignored) { sessions.clear(); }
        boolean keptEmpty = false;
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Session session = sessions.get(i);
            boolean empty = session.messages.isEmpty() && session.plan.isEmpty() && session.goals.isEmpty()
                    && session.workflows.isEmpty() && "新会话".equals(session.title);
            if (empty && keptEmpty) sessions.remove(i); else if (empty) keptEmpty = true;
        }
        if (sessions.isEmpty()) createSession(); else save();
    }

    private static JSONObject toJson(Session session) throws Exception {
        JSONObject json = new JSONObject().put("id", session.id).put("title", session.title)
                .put("presetId", session.presetId).put("createdAt", session.createdAt).put("updatedAt", session.updatedAt);
        JSONArray messages = new JSONArray();
        for (Message message : session.messages) messages.put(new JSONObject().put("role", message.role)
                .put("content", message.content).put("toolName", message.toolName).put("time", message.time));
        JSONArray plan = new JSONArray();
        for (PlanItem item : session.plan) plan.put(new JSONObject().put("id", item.id)
                .put("text", item.text).put("done", item.done));
        JSONArray goals = new JSONArray();
        for (GoalItem item : session.goals) goals.put(new JSONObject().put("id", item.id)
                .put("text", item.text).put("achieved", item.achieved));
        JSONArray workflows = new JSONArray();
        for (WorkflowItem item : session.workflows) workflows.put(new JSONObject().put("id", item.id)
                .put("name", item.name).put("prompt", item.prompt));
        return json.put("messages", messages).put("plan", plan).put("goals", goals).put("workflows", workflows);
    }

    private static Session fromJson(JSONObject json) throws Exception {
        Session session = new Session();
        session.id = json.optString("id", session.id);
        session.title = json.optString("title", "新会话");
        session.presetId = json.optString("presetId", "standard");
        session.createdAt = json.optLong("createdAt", session.createdAt);
        session.updatedAt = json.optLong("updatedAt", session.createdAt);
        JSONArray messages = json.optJSONArray("messages");
        if (messages != null) for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.getJSONObject(i);
            session.messages.add(new Message(item.optString("role"), item.optString("content"),
                    item.optString("toolName"), item.optLong("time")));
        }
        JSONArray plan = json.optJSONArray("plan");
        if (plan != null) for (int i = 0; i < plan.length(); i++) {
            JSONObject source = plan.getJSONObject(i);
            PlanItem item = new PlanItem(source.optString("text"));
            item.id = source.optString("id", item.id); item.done = source.optBoolean("done");
            session.plan.add(item);
        }
        JSONArray goals = json.optJSONArray("goals");
        if (goals != null) for (int i = 0; i < goals.length(); i++) {
            JSONObject source = goals.getJSONObject(i); GoalItem item = new GoalItem(source.optString("text"));
            item.id = source.optString("id", item.id); item.achieved = source.optBoolean("achieved"); session.goals.add(item);
        }
        JSONArray workflows = json.optJSONArray("workflows");
        if (workflows != null) for (int i = 0; i < workflows.length(); i++) {
            JSONObject source = workflows.getJSONObject(i); WorkflowItem item = new WorkflowItem(
                    source.optString("name"), source.optString("prompt"));
            item.id = source.optString("id", item.id); session.workflows.add(item);
        }
        return session;
    }
}
