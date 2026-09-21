package com.fengnanrui.dshandroid;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** A private process group. User commands start only after the owner knows how to cancel the group. */
final class ManagedShell implements AutoCloseable {
    final Process process;
    private final int group;
    private boolean closed;

    private ManagedShell(Process process, int group) { this.process = process; this.group = group; }

    static ManagedShell start(File directory, String command, int timeoutSeconds) throws Exception {
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) throw new IllegalArgumentException("无效的命令超时");
        Process process = new ProcessBuilder("/system/bin/toybox", "setsid", "/system/bin/sh", "-c",
                "printf '%s\\n' \"$$\"; IFS= read -r ready || exit; group=$$; "
                        + "(sleep \"$2\"; kill -KILL -- \"-$group\") </dev/null >/dev/null 2>&1 & "
                        + "exec /system/bin/sh -c \"$1\"",
                "dsh-managed", command, Integer.toString(timeoutSeconds))
                .directory(directory).redirectErrorStream(true).start();
        FutureTask<String> header = new FutureTask<>(() -> new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine());
        Thread handshake = new Thread(header, "dsh-shell-handshake");
        handshake.setDaemon(true); handshake.start();
        ManagedShell shell = null;
        try {
            String value = header.get(2, TimeUnit.SECONDS);
            int group = Integer.parseInt(value == null ? "" : value);
            if (group <= 1) throw new IllegalStateException("无效的命令进程组");
            shell = new ManagedShell(process, group);
            // Nothing after the header can be buffered before this gate opens.
            process.getOutputStream().write("run\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            return shell;
        } catch (Exception error) {
            if (shell != null) shell.close();
            else process.destroyForcibly(); // The command has not passed the gate yet.
            process.getInputStream().close();
            throw error;
        } finally { header.cancel(true); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        try {
            // Killing only sh (or toybox timeout) leaves pipelines and grandchildren running.
            Os.kill(-group, OsConstants.SIGKILL);
            closed = true;
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ESRCH) closed = true;
            else throw new IllegalStateException("无法停止命令进程组", error);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
