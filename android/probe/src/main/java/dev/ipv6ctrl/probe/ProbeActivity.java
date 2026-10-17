package dev.ipv6ctrl.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.WindowInsets;
import android.widget.TextView;
import android.widget.ScrollView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.json.JSONObject;

/** Separate process/package: proves the installed Xposed hook, not the manager's policy function. */
public final class ProbeActivity extends Activity {
    public static String moduleStatus() { return "not-injected"; }
    private static volatile boolean running;
    private static volatile String lastReport;
    private static java.lang.ref.WeakReference<ProbeActivity> current = new java.lang.ref.WeakReference<>(null);
    private TextView text;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this);
        current = new java.lang.ref.WeakReference<>(this);
        text.setTextSize(17);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad,pad,pad,pad);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            text.setOnApplyWindowInsetsListener((v,insets)->{
                var bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                v.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);return insets;
            });
        }
        text.setText("IPV6 Ctrl 独立探针\n正在检测…\n联网检测每次仅请求 ipify IPv4 / IPv6 API 各一次，无自动重试，不保存公网 IP。");
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(text); setContentView(scroll);
        // Rotation and returning to this activity must not multiply API requests.
        if (running) return;
        if (state != null && lastReport != null) { showReport(lastReport); return; }
        running = true;
        boolean online = getIntent().getBooleanExtra("online", true);
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                result.put("testedAt", System.currentTimeMillis());
                result.put("hook", moduleStatus());
                result.put("ipv4", InetAddress.getByName("127.0.0.1") instanceof Inet4Address);
                try {
                    result.put("ipv6", InetAddress.getByName("::1").getAddress().length == 16);
                } catch (UnknownHostException expected) { result.put("ipv6", false); }
                // Local parser checks are independent of external connectivity.
                InetAddress[] loopbacks = InetAddress.getAllByName("");
                result.put("firstFamily", loopbacks[0] instanceof Inet4Address ? 4 : 6);
                int v4 = 0, v6 = 0;
                for (InetAddress a : loopbacks) { if (a instanceof Inet4Address) v4++; else v6++; }
                result.put("v4Count", v4);
                result.put("v6Count", v6);
            } catch (Exception error) {
                try { result.put("error", error.getClass().getSimpleName()); } catch (Exception ignored) {}
            }
            try {
                result.put("online", online);
                if (online) {
                    OnlineCheck.Result v4 = OnlineCheck.query(4), v6 = OnlineCheck.query(6);
                    result.put("onlineV4", v4.reachable()); result.put("onlineV6", v6.reachable());
                    result.put("onlineV4Detail", v4.detail()); result.put("onlineV6Detail", v6.detail());
                    result.put("endpoint", "ipify");
                }
            } catch (Exception ignored) {}
            String report = result.toString();
            Log.i("IPV6CtrlProbe", report);
            sendBroadcast(new Intent("dev.ipv6ctrl.PROBE_RESULT").setPackage("dev.ipv6ctrl").putExtra("report", report));
            lastReport = report; running = false;
            runOnUiThread(() -> { ProbeActivity activity = current.get(); if(activity != null) activity.showReport(report); });
        }, "ipv6-probe").start();
    }

    private void showReport(String report) {
        text.setText("IPV6 Ctrl 独立探针\n\n" + report + "\n\nIPv6 API 成功表示此次网络路径可用；失败不能单独证明系统已阻断。\n返回管理应用查看结果。");
    }
}
