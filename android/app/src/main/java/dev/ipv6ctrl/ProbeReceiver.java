package dev.ipv6ctrl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only the separately signed-with-us probe can send this signature-protected report. */
public final class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"dev.ipv6ctrl.PROBE_RESULT".equals(intent.getAction())) return;
        String report = intent.getStringExtra("report");
        if (report == null || report.length() > 4096) return;
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
                .putString("probe_report", report).putLong("probe_time", System.currentTimeMillis()).apply();
    }
}
