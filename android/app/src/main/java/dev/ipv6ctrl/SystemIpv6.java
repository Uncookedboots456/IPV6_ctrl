package dev.ipv6ctrl;

import android.content.Context;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed script via stdin: no writable executable or user text in the root command. */
final class SystemIpv6 {
    static final long UNKNOWN_MILLIS = -1L;
    record Status(String state, int disabled, int total, boolean restorable,
                  String daemon, boolean managed, String lastSweep,
                  String desired, String bootApply, long lastSweepBoottime, long lastSweepAge,
                  boolean lockSupported) {}

    // elapsed is monotonic and drives freshness; wallTime is display-only.
    record Reading(Status status,long elapsed,long wallTime,String error) {}
    static volatile Reading latest = new Reading(null,0,0,null);
    private static final AtomicLong changeCounter = new AtomicLong();
    static long changeCount() { return changeCounter.get(); }

    static boolean actionAllowed(String action) {
        return action.equals("status")||action.equals("block")||action.equals("restore")||action.equals("pause")
                ||action.equals("enable")||action.equals("toggle")||action.equals("lock");
    }

    static synchronized Status run(Context context,String action) {
        if (!actionAllowed(action)) throw new IllegalArgumentException("无效系统操作");
        boolean changes=!action.equals("status");
        boolean paramsChanged=changes&&!action.equals("pause");
        try {
            Status result=execute(context,action);
            var preferences=context.getSharedPreferences("ui",0).edit().putBoolean("root_ready",true);
            if(changes)preferences.remove("tile_error");
            preferences.apply();
            latest=new Reading(result,SystemClock.elapsedRealtime(),System.currentTimeMillis(),null);
            return result;
        } catch(RuntimeException error) {
            latest=new Reading(null,SystemClock.elapsedRealtime(),System.currentTimeMillis(),error.getMessage());throw error;
        } finally {
            if(paramsChanged)changeCounter.incrementAndGet();
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
                String code = values.getProperty("error", "");
                if (!code.isEmpty()) throw new IllegalStateException(explain(code));
                // Older v2 Runtimes exit 2 for the unknown lock action and print no status.
                if (action.equals("lock") && process.exitValue() == 2)
                    throw new IllegalStateException("配套 Root 模块不支持跨重启锁定；请先更新 Runtime 模块后重试，本次不会降级为仅本次启用。");
                throw new IllegalStateException("Root 操作失败（请确认 su 授权）；请刷新核对");
            }
            String state = values.getProperty("state", "unknown");
            if (!state.equals("blocked") && !state.equals("enabled") && !state.equals("mixed"))
                throw new IllegalStateException("无法确认系统状态");
            int disabled = count(values, "disabled"), total = count(values, "total");
            if (total < 2 || disabled > total) throw new IllegalStateException("Root 状态计数无效，无法确认系统状态");
            String daemon = values.getProperty("daemon", "missing");
            if (!daemon.equals("running") && !daemon.equals("stopped") && !daemon.equals("missing")) daemon = "unknown";
            String lastSweep = oneOf(values, "last_sweep", "ok", "idle", "write-error", "snapshot-error",
                    "boot-apply-failed", "boot-apply-interrupted", "unknown");
            return new Status(state, disabled, total, Boolean.parseBoolean(values.getProperty("restorable")),
                    daemon, Boolean.parseBoolean(values.getProperty("managed")), lastSweep,
                    oneOf(values, "desired", "locked", "none", "unknown"),
                    oneOf(values, "boot_apply", "applied", "failed", "interrupted", "manual", "none", "unknown"),
                    stamp(values, "last_sweep_boottime"), stamp(values, "last_sweep_age"),
                    values.getProperty("desired") != null);
        } catch (IllegalStateException e) { throw e; }
        catch (java.io.IOException e) {
            android.util.Log.w("IPV6CtrlRoot", "Cannot communicate with su", e);
            throw new IllegalStateException("无法调用 su；请在 Root 管理器授予 IPV6 Ctrl 超级用户权限后重试。若曾中断操作，请先刷新核对系统状态。", e);
        }
        catch (Exception e) { throw new IllegalStateException("无法执行 Root 操作：" + e.getClass().getSimpleName(), e); }
        finally { if (process != null) process.destroy();trace(action,"finished",started); }
    }
    private static int count(Properties values,String key) {
        String raw=values.getProperty(key);
        if(raw==null||!raw.matches("[0-9]{1,9}")) throw new IllegalStateException("Root 状态计数无效，无法确认系统状态");
        return Integer.parseInt(raw);
    }
    private static long stamp(Properties values,String key) {
        String raw=values.getProperty(key);
        if(raw==null||!raw.matches("[0-9]{1,19}")) return UNKNOWN_MILLIS;
        try { long value=Long.parseLong(raw); return value>=0?value:UNKNOWN_MILLIS; }
        catch(NumberFormatException error){ return UNKNOWN_MILLIS; }
    }
    private static String oneOf(Properties values,String key,String... allowed) {
        String raw=values.getProperty(key);
        if(raw!=null) for(String candidate:allowed) if(candidate.equals(raw)) return raw;
        return "unknown";
    }
    private static String explain(String code) {
        return switch(code) {
            case "runtime-update-required" -> "请先更新配套 Root 模块，再使用 ipv6 快捷开关或跨重启锁定。";
            case "runtime-module-required" -> "未安装或未启用配套 Root Runtime 模块；请安装模块并重启后重试。";
            case "runtime-module-disabled" -> "Root Runtime 模块已停用或正在移除；请启用后重试。";
            case "runtime-daemon-not-running" -> "Root Runtime 服务未运行；请安装或重启模块后重试。";
            case "lock-persist-failed-current-only" -> "本次 IPv4 已生效，但跨重启锁定写入失败；重启后不会保持，请刷新核对后重试。";
            case "lock-resolve-failed" -> "本次锁定已生效并持久保存，但无法更新本次开机应用历史；界面可能仍显示旧失败，请刷新核对。";
            case "desired-clear-failed" -> "无法清除跨重启锁定；为避免误解锁，操作已中止，请刷新核对。";
            case "boot-apply-failed" -> "本次开机自动锁定失败；不会自动重试，请刷新核对。";
            case "boot-apply-interrupted" -> "上次开机自动锁定被中断；本次不会自动重试，请刷新核对。";
            case "snapshot-failed" -> "无法保存修改前快照；本次未锁定，请刷新核对。";
            case "restore-failed-snapshot-kept" -> "恢复未完成；已保留快照，参数可能尚未完全恢复，请刷新核对。";
            default -> "Root 操作失败（"+code+"）；请刷新核对。";
        };
    }
    private static void trace(String action,String stage,long started){
        if(BuildConfig.DEBUG)android.util.Log.d("IPV6CtrlRoot",action+" "+stage+" elapsed="+(SystemClock.elapsedRealtime()-started));
    }
}
