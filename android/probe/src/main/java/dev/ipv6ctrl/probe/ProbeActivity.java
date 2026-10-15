package dev.ipv6ctrl.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.json.JSONObject;

/** Separate process/package: proves the installed Xposed hook, not the manager's policy function. */
public final class ProbeActivity extends Activity {
    public static String moduleStatus() { return "not-injected"; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setTextSize(17);
        text.setPadding(32,96,32,64);
        text.setText("IPV6 Ctrl 独立探针\n正在检测本机回环地址…");
        text.setTextIsSelectable(true);
        setContentView(text);
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                result.put("hook", moduleStatus());
                result.put("ipv4", InetAddress.getByName("127.0.0.1") instanceof Inet4Address);
                try {
                    result.put("ipv6", InetAddress.getByName("::1").getAddress().length == 16);
                } catch (UnknownHostException expected) { result.put("ipv6", false); }
                // AOSP returns IPv6 then IPv4 loopback for the empty host. No external DNS or traffic.
                InetAddress[] loopbacks = InetAddress.getAllByName("");
                result.put("firstFamily", loopbacks[0] instanceof Inet4Address ? 4 : 6);
                int v4 = 0, v6 = 0;
                for (InetAddress a : loopbacks) { if (a instanceof Inet4Address) v4++; else v6++; }
                result.put("v4Count", v4);
                result.put("v6Count", v6);
            } catch (Exception error) {
                try { result.put("error", error.getClass().getSimpleName()); } catch (Exception ignored) {}
            }
            String report = result.toString();
            Log.i("IPV6CtrlProbe", report);
            sendBroadcast(new Intent("dev.ipv6ctrl.PROBE_RESULT").setPackage("dev.ipv6ctrl").putExtra("report", report));
            runOnUiThread(() -> text.setText("IPV6 Ctrl 独立探针\n\n" + report + "\n\n仅使用回环地址，不访问互联网。\n返回管理应用查看结果。"));
        }, "ipv6-probe").start();
    }
}
