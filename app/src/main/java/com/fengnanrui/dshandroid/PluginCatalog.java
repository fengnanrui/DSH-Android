package com.fengnanrui.dshandroid;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native compatibility inventory derived from the current upstream base, web and preset compositions. */
public final class PluginCatalog {
    public record Entry(String id, String moduleName, Kind kind, Availability availability) {}
    public enum Kind { RUNTIME, TOOL, UI, PROVIDER, STORAGE, POLICY }
    public enum Availability { NATIVE, COMPATIBILITY_ONLY }

    private static final String[] IDS = {
            "timer", "hmr", "llm", "session", "typert", "typert-loader", "typert-gateway",
            "session-title", "session-title-llm", "user-questions", "agent", "agent-default-model",
            "jobs", "llm-retry", "settings", "credentials", "llm-pi-ai", "session-persistence-jsonl",
            "attachment-local", "session-query-sqlite", "session-projection", "session-telemetry-otel",
            "subprocess", "sandbox", "sandbox-policy", "bash-sandbox", "pwsh-sandbox", "approval",
            "permission", "shell-env", "tool-bash", "tool-pwsh", "tool-jobs", "fs-observation-policy",
            "tool-fs", "tool-fs-search", "agent-instructions", "skill", "skill-filesystem", "skill-badge",
            "tool-skill", "commands", "command-feedback", "goal", "goal-round-driver", "command-goal",
            "plan-mode", "token-meter", "compaction-basic", "command-compact", "subagent",
            "subagent-spawn-in-process", "subagent-fork-in-process", "tool-subagent-control",
            "tool-subagent-list-agents", "tool-subagent", "tool-subagent-fork", "tool-subagent-report",
            "workflow-worker-thread", "tool-workflow", "timeout-policy", "spill-local", "spill-policy",
            "session-checkpoint-policy", "tool-result-pruner", "tool-todo", "tool-goal", "tool-ralph",
            "tool-str-replace-editor", "repeat-tool-reminder", "web", "web-search-deepseek", "tool-web",
            "tools", "system-prompt", "agent-loop", "fs-sandbox", "llm-deepseek", "code-runtime",
            "storage", "storage-json", "storage-domain", "message-feedback", "session-log-download",
            "workspace", "session-projection-cache", "session-stats", "directory-picker", "plugin-inventory",
            "api-gateway", "cordis-host-runner", "web-startup", "webserver", "web-runtime", "client-hmr",
            "modules", "connection", "api-remotes", "client-runtime", "cordis-client-runner", "ui-theme",
            "locale", "ui-layout", "ui-sidebar", "ui-settings", "ui-settings-general", "ui-settings-models",
            "ui-settings-plugin-inventory", "ui-conversation", "ui-tool", "ui-cordis", "ui-workflow-run",
            "ui-deliverables", "ui-workspace", "ui-input-trigger", "ui-commands", "ui-skill", "ui-subagent",
            "ui-jobs", "ui-goal", "ui-message-feedback", "ui-model-selection", "ui-permission",
            "ui-agent-preset", "ui-settings-plugins", "ui-plan", "ui-user-questions", "ui-trajectory",
            "agent-presets", "persona", "planning", "compaction", "delegation", "tool-subagent-codex",
            "tool-subagent-claude-code", "tool-ask-user"
    };

    private static final Set<String> TOOL_IDS = new HashSet<>(Arrays.asList(
            "tool-bash", "tool-pwsh", "tool-jobs", "tool-fs", "tool-fs-search", "tool-skill",
            "tool-subagent-control", "tool-subagent-list-agents", "tool-subagent", "tool-subagent-fork",
            "tool-subagent-report", "tool-workflow", "tool-todo", "tool-goal", "tool-ralph",
            "tool-str-replace-editor", "tool-web", "tool-ask-user"));

    /** Only these entries have a concrete Android implementation and a meaningful switch. */
    private static final Set<String> NATIVE_IDS = new HashSet<>(Arrays.asList(
            "llm", "agent", "agent-loop", "timer", "tool-bash", "tool-jobs", "tool-fs",
            "tool-fs-search", "tool-str-replace-editor", "tool-web", "tool-todo", "tool-goal",
            "tool-workflow", "tool-skill", "tool-subagent", "tool-ask-user", "plugin-inventory",
            "settings", "code-runtime", "agent-presets"));

    private PluginCatalog() {}

    public static List<Entry> all() {
        List<Entry> result = new ArrayList<>(IDS.length);
        for (String id : IDS) result.add(new Entry(id, moduleName(id), kind(id), availability(id)));
        return result;
    }

    public static int nativeCount() { return NATIVE_IDS.size(); }

    public static boolean isNative(String id) { return NATIVE_IDS.contains(id); }

    public static boolean enabled(SharedPreferences settings, String id) {
        if (!isNative(id)) return false;
        return !settings.getStringSet("disabled_plugins", Collections.emptySet()).contains(id);
    }

    public static void setEnabled(SharedPreferences settings, String id, boolean enabled) {
        if (!isNative(id)) return;
        Set<String> disabled = new HashSet<>(settings.getStringSet("disabled_plugins", Collections.emptySet()));
        if (enabled) disabled.remove(id); else disabled.add(id);
        settings.edit().putStringSet("disabled_plugins", disabled).apply();
    }

    private static Availability availability(String id) {
        return isNative(id) ? Availability.NATIVE : Availability.COMPATIBILITY_ONLY;
    }

    private static Kind kind(String id) {
        if (id.startsWith("ui-")) return Kind.UI;
        if (TOOL_IDS.contains(id)) return Kind.TOOL;
        if (id.contains("llm") || id.contains("web-search")) return Kind.PROVIDER;
        if (id.contains("storage") || id.contains("persistence") || id.contains("attachment") || id.contains("spill")) return Kind.STORAGE;
        if (id.contains("sandbox") || id.contains("permission") || id.contains("approval") || id.contains("policy")) return Kind.POLICY;
        return Kind.RUNTIME;
    }

    private static String moduleName(String id) {
        if ("timer".equals(id) || "hmr".equals(id)) return "@deepseek-ai/cordis-plugin-" + id;
        return "@deepseek-ai/dsh-" + id;
    }
}
