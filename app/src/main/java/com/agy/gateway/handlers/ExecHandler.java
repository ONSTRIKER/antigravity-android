package com.agy.gateway.handlers;

import android.content.Context;
import android.util.Log;

import com.agy.BuildConfig;
import com.agy.log.CommandHistory;
import com.agy.log.CommandRecord;
import com.agy.log.LogRingBuffer;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Handles command execution requests with detailed telemetry, outputs, and exit codes.
 */
public final class ExecHandler {

    private static final String TAG = "ExecHandler";
    private static final int MAX_STREAM_CHARS = 256 * 1024;
    private static final String TRUNCATED_SUFFIX = "\n...[output truncated at 256 KB]";
    private final Context context;
    private final CommandHistory history;
    private final LogRingBuffer logBuffer;

    public ExecHandler(Context context, CommandHistory history, LogRingBuffer logBuffer) {
        this.context = context.getApplicationContext();
        this.history = history;
        this.logBuffer = logBuffer;
    }

    public JSONObject handle(JSONObject payload) {
        String cmd = payload.optString("cmd", "").trim();
        String source = payload.optString("source", "agent");
        long timeoutMs = payload.optLong("timeout_ms", 30000);
        boolean useRish = payload.optBoolean("rish", false);
        String cwdPath = payload.optString("cwd", context.getFilesDir().getAbsolutePath());

        String cmdId = "cmd_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        long startTime = System.currentTimeMillis();

        if (cmd.isEmpty()) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", "empty command");
            } catch (Exception ignored) {}
            return err;
        }

        if (BuildConfig.DEBUG) {
            String startMsg = String.format("[EXEC] START [%s] (source=%s): %s", cmdId, source, cmd);
            Log.d(TAG, startMsg);
            if (logBuffer != null) {
                logBuffer.append("exec", "DEBUG", startMsg);
            }
        }

        int exitCode = -1;
        StringBuilder stdoutBuf = new StringBuilder();
        StringBuilder stderrBuf = new StringBuilder();
        String status = "ERROR";

        try {
            ProcessBuilder pb;
            if (useRish) {
                pb = new ProcessBuilder(resolveRishPath(), "-c", cmd);
            } else {
                pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            }

            File cwd = new File(cwdPath);
            if (cwd.isDirectory()) {
                pb.directory(cwd);
            }

            String home = context.getFilesDir().getAbsolutePath();
            pb.environment().put("HOME", home);
            pb.environment().put("PATH", home + "/usr/bin:" + home + "/runtime/bin:/system/bin:" + System.getenv("PATH"));
            pb.environment().put("RISH_APPLICATION_ID", context.getPackageName());
            pb.environment().remove("LD_LIBRARY_PATH");

            Process proc = pb.start();
            try {
                if (proc.getOutputStream() != null) {
                    proc.getOutputStream().close();
                }
            } catch (Exception ignored) {
            }

            // Read stdout and stderr in background threads to avoid buffer deadlocks
            Thread outThread = new Thread(() -> drainStream(proc.getInputStream(), stdoutBuf), "StdoutDrain");
            Thread errThread = new Thread(() -> drainStream(proc.getErrorStream(), stderrBuf), "StderrDrain");
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (finished) {
                exitCode = proc.exitValue();
                outThread.join(500);
                errThread.join(500);
                status = (exitCode == 0) ? "SUCCESS" : "FAILED";
            } else {
                proc.destroy();
                try {
                    if (!proc.waitFor(150, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly();
                    }
                } catch (Exception ignored) {
                    try {
                        proc.destroyForcibly();
                    } catch (Exception ignored2) {
                    }
                }
                try {
                    if (proc.getInputStream() != null) proc.getInputStream().close();
                } catch (Exception ignored) {
                }
                try {
                    if (proc.getErrorStream() != null) proc.getErrorStream().close();
                } catch (Exception ignored) {
                }
                outThread.interrupt();
                errThread.interrupt();
                outThread.join(300);
                errThread.join(300);
                status = "TIMEOUT";
                stderrBuf.append("\n[Process timed out after ").append(timeoutMs).append("ms]");
            }
        } catch (Exception e) {
            status = "ERROR";
            stderrBuf.append("\nExecution exception: ").append(e.getMessage());
            Log.w(TAG, "exec error: " + e.getMessage());
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String stdoutStr = stdoutBuf.toString().trim();
        String stderrStr = stderrBuf.toString().trim();

        CommandRecord record = new CommandRecord(cmdId, startTime, cmd, exitCode, durationMs, stdoutStr, stderrStr, status, source);
        if (history != null) {
            history.record(record);
        }

        if (BuildConfig.DEBUG) {
            String endMsg = String.format("[EXEC] DONE [%s] (exit=%d, %dms, status=%s)", cmdId, exitCode, durationMs, status);
            Log.d(TAG, endMsg);
            if (logBuffer != null) {
                logBuffer.append("exec", (exitCode == 0 ? "INFO" : "WARN"), endMsg);
                if (!stderrStr.isEmpty()) {
                    logBuffer.append("exec", "WARN", "[stderr] " + stderrStr);
                }
            }
        }

        JSONObject resp = record.toJson();
        try {
            resp.put("ok", "SUCCESS".equals(status));
        } catch (Exception ignored) {}
        return resp;
    }

    private static void drainStream(InputStream in, StringBuilder out) {
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                if (!truncated) {
                    int remaining = MAX_STREAM_CHARS - out.length();
                    if (remaining > 0) {
                        int toAppend = Math.min(n, remaining);
                        out.append(buf, 0, toAppend);
                        if (toAppend < n) {
                            out.append(TRUNCATED_SUFFIX);
                            truncated = true;
                        }
                    } else {
                        out.append(TRUNCATED_SUFFIX);
                        truncated = true;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String resolveRishPath() {
        File files = context.getFilesDir();
        File[] candidates = new File[]{
                new File(files, "usr/bin/rish"),
                new File(files, "runtime/bin/rish"),
                new File(files, "bin/rish")
        };
        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return new File(files, "usr/bin/rish").getAbsolutePath();
    }
}
