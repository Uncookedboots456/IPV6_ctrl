package dev.ipv6ctrl;

import android.content.Context;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Fixed script via stdin: no writable executable or user text in the root command. */
final class SystemIpv6 {
    record Status(String state, int disabled, int total, boolean restorable,
                  String daemon, boolean managed, String lastSweep) {}

    record Reading(Status status,long time,String error) {}
    static volatile Reading latest = new Reading(null,0,null);

    static synchronized Status run(Context context,String action) {
        if (!action.equals("status") && !action.equals("block") && !action.equals("restore") && !action.equals("pause")
                && !action.equals("enable") && !action.equals("toggle")) throw new IllegalArgumentException("无效系统操作");
        boolean changes=!action.equals("status");
        if(changes)context.getSharedPreferences("ui",0).edit().putLong("system_changed_at",System.currentTimeMillis()).apply();
        try {
            Status result=execute(context,action);
            var preferences=context.getSharedPreferences("ui",0).edit().putBoolean("root_ready",true);
            if(changes)preferences.remove("tile_error");
            preferences.apply();
            latest=new Reading(result,System.currentTimeMillis(),null);
            return result;
        } catch(RuntimeException error) {
            latest=new Reading(null,System.currentTimeMillis(),error.getMessage());throw error;
        } finally {
            if(changes)context.getSharedPreferences("ui",0).edit().putLong("system_changed_at",System.currentTimeMillis()).apply();
        }
    }
    private static Status execute(Context context,String action) {
        Process process = null;
        long started=SystemClock.elapsedRealtime();
        try {
            byte[] script;
            try (var input = context.getAssets().open("system-ipv6.sh"); var bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                script = bytes.toByteArray();
            }
            trace(action,"start",started);
            process = new ProcessBuilder("su", "-c", "sh -s -- " + action).redirectErrorStream(true).start();
            trace(action,"spawned",started);
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
            // Include a blocked stdin pipe in the deadline, not just waitFor after the write.
            Thread writer=new Thread(()->{
                try(var input=running.getOutputStream()){input.write(script);trace(action,"input-sent",started);}
                catch(java.io.IOException error){trace(action,"input-closed",started);}
            },"ipv6-root-input");
            writer.setDaemon(true);writer.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IllegalStateException("Root 操作超时；请刷新核对，必要时恢复快照");
            trace(action,"exited",started);
            reader.join(1000);
            Properties values = new Properties();
            synchronized (output) { values.load(new java.io.StringReader(output.toString(StandardCharsets.UTF_8.name()))); }
            if (process.exitValue() != 0 || values.containsKey("error")) {
                if(values.getProperty("error","").equals("runtime-update-required"))
                    throw new IllegalStateException("请先更新配套 Root 模块，再使用 ipv6 快捷开关");
                throw new IllegalStateException("Root 操作失败（" + values.getProperty("error", "请确认 su 授权") + "）；请刷新核对");
            }
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
        finally { if (process != null) process.destroy();trace(action,"finished",started); }
    }
    private static void trace(String action,String stage,long started){
        if(BuildConfig.DEBUG)android.util.Log.d("IPV6CtrlRoot",action+" "+stage+" elapsed="+(SystemClock.elapsedRealtime()-started));
    }
}
