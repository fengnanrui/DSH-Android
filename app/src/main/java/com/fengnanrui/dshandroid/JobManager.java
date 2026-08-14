package com.fengnanrui.dshandroid;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
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
        final Process process;
        volatile boolean timedOut;

        RunningJob(int id, String command, File log, Process process) {
            this.id = id; this.command = command; this.log = log;
            this.process = process; this.startedAt = System.currentTimeMillis();
        }
    }

    private final File workspace;
    private final AppStore store;
    private final Map<Integer, RunningJob> jobs = new LinkedHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private int nextId = 1;

    JobManager(File workspace, AppStore store) {
        this.workspace = workspace; this.store = store;
    }

    public synchronized String start(String command) throws Exception {
        if (command == null || command.trim().isEmpty() || command.length() > 4000)
            throw new IllegalArgumentException("命令为空或过长");
        trimHistory();
        long active = jobs.values().stream().filter(job -> job.process.isAlive()).count();
        if (active >= store.maxConcurrentJobs())
            throw new IllegalStateException("后台 Job 已达到并发上限 " + store.maxConcurrentJobs());

        int id = nextId++;
        File log = new File(workspace, ".dsh-job-" + System.currentTimeMillis() + "-" + id + ".log");
        ProcessBuilder builder = boundedProcess(command, store.jobTimeoutSeconds());
        Process process = builder.directory(workspace).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start();
        RunningJob job = new RunningJob(id, command, log, process);
        jobs.put(id, job);
        watchdog.schedule(() -> timeout(id), store.jobTimeoutSeconds(), TimeUnit.SECONDS);
        return "后台任务 #" + id + " 已启动，最长 " + store.jobTimeoutSeconds()
                + " 秒，日志=" + log.getName();
    }

    public synchronized String list() {
        trimHistory();
        if (jobs.isEmpty()) return "没有后台任务";
        StringBuilder out = new StringBuilder();
        for (RunningJob job : jobs.values()) {
            String state;
            if (job.process.isAlive()) state = "运行中";
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
        if (job.process.isAlive()) job.process.destroyForcibly();
        return "已停止后台任务 #" + id;
    }

    private synchronized void timeout(int id) {
        RunningJob job = jobs.get(id);
        if (job != null && job.process.isAlive()) {
            job.timedOut = true;
            job.process.destroyForcibly();
        }
    }

    private ProcessBuilder boundedProcess(String command, int timeoutSeconds) {
        File toybox = new File("/system/bin/toybox");
        if (toybox.isFile()) return new ProcessBuilder(toybox.getPath(), "timeout", "-s", "KILL",
                timeoutSeconds + "s", "/system/bin/sh", "-c", command);
        return new ProcessBuilder("/system/bin/sh", "-c", command);
    }

    private void trimHistory() {
        while (jobs.size() >= HISTORY_LIMIT) {
            Integer removable = null;
            for (Map.Entry<Integer, RunningJob> entry : jobs.entrySet()) {
                if (!entry.getValue().process.isAlive()) { removable = entry.getKey(); break; }
            }
            if (removable == null) break;
            jobs.remove(removable);
        }
    }

    private static int exitValue(Process process) {
        try { return process.exitValue(); } catch (Exception ignored) { return -1; }
    }

    @Override public synchronized void close() {
        for (RunningJob job : jobs.values()) if (job.process.isAlive()) job.process.destroyForcibly();
        watchdog.shutdownNow();
    }
}
