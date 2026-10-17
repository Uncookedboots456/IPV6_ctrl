package dev.ipv6ctrl;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Fixed script via stdin: no writable executable or user text in the root command. */
final class SystemIpv6 {
    record Status(String state, int disabled, int total, boolean restorable,
                  String daemon, boolean managed, String lastSweep) {}

    static Status run(Context context, String action) {
        if (!action.equals("status") && !action.equals("block") && !action.equals("restore") && !action.equals("pause"))
            throw new IllegalArgumentException("无效系统操作");
        Process process = null;
        try {
            byte[] script;
            try (var input = context.getAssets().open("system-ipv6.sh"); var bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                script = bytes.toByteArray();
            }
            process = new ProcessBuilder("su", "-c", "sh -s -- " + action).redirectErrorStream(true).start();
            Process running = process;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (var input = running.getInputStream()) {
                    byte[] chunk = new byte[1024]; int count;
                    while ((count = input.read(chunk)) != -1) {
                        synchronized (output) { if (output.size() < 8192) output.write(chunk, 0, Math.min(count, 8192-output.size())); }
                    }
                } catch (Exception ignored) {}
            }, "ipv6-root-output");
            reader.setDaemon(true); reader.start();
            try (var input = process.getOutputStream()) { input.write(script); }
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IllegalStateException("Root 操作超时；请刷新核对，必要时恢复快照");
            reader.join(1000);
            Properties values = new Properties();
            synchronized (output) { values.load(new java.io.StringReader(output.toString(StandardCharsets.UTF_8.name()))); }
            if (process.exitValue() != 0 || values.containsKey("error"))
                throw new IllegalStateException("Root 操作失败（" + values.getProperty("error", "请确认 su 授权") + "）；请刷新核对");
            String state = values.getProperty("state", "unknown");
            if (!state.equals("blocked") && !state.equals("enabled") && !state.equals("mixed"))
                throw new IllegalStateException("无法确认系统状态");
            return new Status(state, Integer.parseInt(values.getProperty("disabled")),
                    Integer.parseInt(values.getProperty("total")), Boolean.parseBoolean(values.getProperty("restorable")),
                    values.getProperty("daemon", "missing"), Boolean.parseBoolean(values.getProperty("managed")),
                    values.getProperty("last_sweep", "unknown"));
        } catch (IllegalStateException e) { throw e; }
        catch (java.io.IOException e) {
            android.util.Log.w("IPV6CtrlRoot", "Cannot communicate with su", e);
            throw new IllegalStateException("无法调用 su；请在 Root 管理器授予 IPV6 Ctrl 超级用户权限后重试。若曾中断操作，请先刷新核对系统状态。", e);
        }
        catch (Exception e) { throw new IllegalStateException("无法执行 Root 操作：" + e.getClass().getSimpleName(), e); }
        finally { if (process != null) process.destroy(); }
    }
}
