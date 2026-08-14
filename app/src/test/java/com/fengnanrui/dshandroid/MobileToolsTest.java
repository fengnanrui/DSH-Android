package com.fengnanrui.dshandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashSet;

public final class MobileToolsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void blocksPathTraversal() throws Exception {
        File workspace = temporary.newFolder("workspace");
        MobileTools tools = new MobileTools(workspace, null, null);
        try {
            tools.resolve("../outside.txt");
            fail("Traversal must be rejected");
        } catch (SecurityException expected) {
            assertTrue(expected.getMessage().contains("工作区"));
        }
    }

    @Test public void readsWritesSearchesAndListsInsideWorkspace() throws Exception {
        File workspace = temporary.newFolder("workspace");
        MobileTools tools = new MobileTools(workspace, null, null);
        assertTrue(tools.execute("write_file", new JSONObject().put("path", "notes/a.txt")
                .put("content", "hello mobile agent")).contains("已写入"));
        assertTrue(tools.execute("read_file", new JSONObject().put("path", "notes/a.txt"))
                .contains("mobile agent"));
        assertTrue(tools.execute("search_files", new JSONObject().put("query", "mobile"))
                .contains("notes"));
        assertTrue(tools.execute("list_files", new JSONObject().put("path", "notes"))
                .contains("a.txt"));
    }

    @Test public void declaresExpectedApprovalBoundaries() throws Exception {
        JSONArray definitions = MobileTools.definitions();
        assertTrue(definitions.length() >= 10);
        assertTrue(MobileTools.requiresApproval("run_shell"));
        assertTrue(MobileTools.requiresApproval("fetch_url"));
        assertFalse(MobileTools.requiresApproval("read_file"));
    }

    @Test public void inventoryContainsAllStableUpstreamPluginIds() {
        assertTrue(PluginCatalog.all().size() == 136);
        HashSet<String> ids = new HashSet<>();
        for (PluginCatalog.Entry entry : PluginCatalog.all()) ids.add(entry.id());
        assertTrue(ids.size() == PluginCatalog.all().size());
    }
}
