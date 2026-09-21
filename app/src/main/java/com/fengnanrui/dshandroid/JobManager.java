package com.fengnanrui.dshandroid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns every background process so Jobs are bounded, visible and cleaned up with the app runtime. */
public final class JobManager implements AutoCloseable {
    private static final int HISTORY_LIMIT = 20;

    private static final class RunningJob {
        final int id;
        final String command;
        final File log;
        final long startedAt;
        final ManagedShell shell;
        final Process process;
        volatile boolean timedOut;
        volatile boolean outputLimit;
        volatile String error;
        volatile boolean finished;

        RunningJob(int id, String command, File log, ManagedShell shell) {
            this.id = id; this.command = command; this.log = log;
            this.shell = shell; this.process = shell.process; this.startedAt = System.currentTimeMillis();
        }
    }

    private final File workspace;
    private final AppStore store;
    private final Map<Integer, RunningJob> jobs = new LinkedHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService readers = Executors.newCachedThreadPool();
    private boolean closed;
    private int nextId = 1;

    JobManager(File workspace, AppStore store) {
        this.workspace = workspace; this.store = store;
    }

    public synchronized String start(String command) throws Exception {
        if (closed) throw new IllegalStateException("后台任务管理器已关闭");
        if (command == null || command.trim().isEmpty() || command.length() > 4000)
            throw new IllegalArgumentException("命令为空或过长");
        trimHistory();
        long active = jobs.values().stream().filter(job -> !job.finished).count();
        if (active >= store.maxConcurrentJobs())
            throw new IllegalStateException("后台 Job 已达到并发上限 " + store.maxConcurrentJobs());

        int id = nextId++;
        File log = new File(workspace, ".dsh-job-" + System.currentTimeMillis() + "-" + id + ".log");
        ManagedShell shell = ManagedShell.start(workspace, command, store.jobTimeoutSeconds());
        RunningJob job = new RunningJob(id, command, log, shell);
        jobs.put(id, job);
        int outputBytes = store.shellOutputKb() * 1024;
        readers.execute(() -> capture(job, outputBytes));
        watchdog.schedule(() -> timeout(job), store.jobTimeoutSeconds(), TimeUnit.SECONDS);
        return "后台任务 #" + id + " 已启动，最长 " + store.jobTimeoutSeconds()
                + " 秒，日志=" + log.getName();
    }

    public synchronized String list() {
        trimHistory();
        if (jobs.isEmpty()) return "没有后台任务";
        StringBuilder out = new StringBuilder();
        for (RunningJob job : jobs.values()) {
            String state;
            if (!job.finished) state = "运行中";
            else if (job.error != null) state = "失败：" + job.error;
            else if (job.outputLimit) state = "日志达到上限，已终止";
            else if (job.timedOut) state = "已超时终止";
            else state = "已结束(exit=" + exitValue(job.process) + ")";
            out.append('#').append(job.id).append(' ').append(state)
                    .append(" · ").append(job.log.getName()).append('\n');
        }
        return out.toString().trim();
    }

    public synchronized String stop(int id) {
        RunningJob job = jobs.get(id);
        if (job == null) return "任务不存在";
        job.shell.close();
        return "已停止后台任务 #" + id;
    }

    private void timeout(RunningJob job) {
        if (!job.finished) {
            job.timedOut = true;
            stopOwned(job);
        }
    }

    private void capture(RunningJob job, int limit) {
        try (InputStream input = job.process.getInputStream(); FileOutputStream output = new FileOutputStream(job.log)) {
            byte[] buffer = new byte[4096];
            int total = 0, count;
            while ((count = input.read(buffer)) != -1) {
                int retained = Math.min(count, limit - total);
                if (retained > 0) { output.write(buffer, 0, retained); output.flush(); total += retained; }
                if (count > retained) { job.outputLimit = true; stopOwned(job); break; }
            }
            job.process.waitFor();
        } catch (Exception error) {
            if (job.process.isAlive()) job.error = error.getClass().getSimpleName();
        } finally { stopOwned(job); job.finished = true; }
    }

    private static void stopOwned(RunningJob job) {
        try { job.shell.close(); }
        catch (IllegalStateException error) { job.error = error.getMessage(); }
    }

    private void trimHistory() {
        while (jobs.size() >= HISTORY_LIMIT) {
            Integer removable = null;
            for (Map.Entry<Integer, RunningJob> entry : jobs.entrySet()) {
                if (entry.getValue().finished) { removable = entry.getKey(); break; }
            }
            if (removable == null) break;
            jobs.remove(removable);
        }
    }

    private static int exitValue(Process process) {
        try { return process.exitValue(); } catch (Exception ignored) { return -1; }
    }

    @Override public synchronized void close() {
        closed = true;
        for (RunningJob job : jobs.values()) stopOwned(job);
        watchdog.shutdownNow();
        readers.shutdownNow();
    }
}
