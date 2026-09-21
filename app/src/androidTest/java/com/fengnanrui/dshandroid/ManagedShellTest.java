package com.fengnanrui.dshandroid;

import android.test.AndroidTestCase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

public final class ManagedShellTest extends AndroidTestCase {
    public void testStopKillsPipelineDescendantsAndIsIdempotent() throws Exception {
        File directory = Files.createTempDirectory(getContext().getCacheDir().toPath(), "shell-group-").toFile();
        File ready = new File(directory, "ready"), escaped = new File(directory, "escaped");
        ManagedShell shell = ManagedShell.start(directory,
                "(echo ready > ready; sleep 1; echo leaked > escaped) & wait", 5);
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!ready.exists() && System.nanoTime() < end) Thread.sleep(20);
            assertTrue("Child must start before cancellation", ready.exists());
            shell.close(); shell.close();
            assertTrue(shell.process.waitFor(2, TimeUnit.SECONDS));
            Thread.sleep(1200);
            assertFalse("Stopping the owner must stop its child too", escaped.exists());
        } finally { shell.close(); ready.delete(); escaped.delete(); directory.delete(); }
    }

    public void testCapturesOutputAndCommandExitStatus() throws Exception {
        try (ManagedShell shell = ManagedShell.start(getContext().getCacheDir(), "printf 'hello'; exit 7", 5)) {
            assertTrue(shell.process.waitFor(2, TimeUnit.SECONDS));
            assertEquals(7, shell.process.exitValue());
            byte[] bytes = new byte[32];
            int length = shell.process.getInputStream().read(bytes);
            assertEquals("hello", new String(bytes, 0, length, StandardCharsets.UTF_8));
        }
    }

    public void testDeadlineDoesNotDependOnJavaWatchdog() throws Exception {
        try (ManagedShell shell = ManagedShell.start(getContext().getCacheDir(), "sleep 30 & wait", 1)) {
            assertTrue("Native deadline must stop the group without Java scheduling",
                    shell.process.waitFor(3, TimeUnit.SECONDS));
            assertTrue(shell.process.exitValue() != 0);
        }
    }

    public void testJobLogLimitStopsProducerAndCloseRejectsNewJobs() throws Exception {
        AppStore store = new AppStore(getContext(), "job-log-bound");
        store.savePluginConfig(20, 16, 3, 5, 30, 1);
        JobManager jobs = store.jobs();
        try {
            jobs.start("while true; do printf '0123456789abcdef0123456789abcdef'; done");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (jobs.list().contains("运行中") && System.nanoTime() < end) Thread.sleep(20);
            assertTrue(jobs.list(), jobs.list().contains("日志达到上限"));
            File[] logs = store.workspace().listFiles((dir, name) -> name.startsWith(".dsh-job-"));
            assertNotNull(logs); assertTrue(logs.length > 0);
            for (File log : logs) assertTrue("Log must be bounded", log.length() <= 16 * 1024);
            jobs.close();
            try { jobs.start("echo forbidden"); fail("Closed manager accepted a job"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("已关闭")); }
        } finally {
            jobs.close();
            File[] logs = store.workspace().listFiles((dir, name) -> name.startsWith(".dsh-job-"));
            if (logs != null) for (File log : logs) log.delete();
        }
    }
}
