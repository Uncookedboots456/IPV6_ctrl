package dev.ipv6ctrl;

import android.content.SharedPreferences;
import android.util.Log;
import java.net.InetAddress;
import java.util.Collections;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class Ipv6Module extends XposedModule {
    private record Rule(AddressPolicy.Mode mode, boolean selected) {}
    private volatile Rule rule = new Rule(AddressPolicy.Mode.SYSTEM, false);
    // Keep a strong reference: framework preferences may store listeners weakly.
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private SharedPreferences preferences;

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        // Never hook the Android framework, the manager, or this module's UI.
        if ((param.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                || "android".equals(pkg) || "org.lsposed.manager".equals(pkg) || "dev.ipv6ctrl".equals(pkg)) return;
        try {
            preferences = getRemotePreferences("policy");
            listener = (prefs, key) -> reload(pkg);
            reload(pkg);
            preferences.registerOnSharedPreferenceChangeListener(listener);
            // AOSP Java resolver entry; numeric literals and Network resolution pass here too.
            var method = Class.forName("java.net.Inet6AddressImpl")
                    .getDeclaredMethod("lookupAllHostAddr", String.class, int.class);
            hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                InetAddress[] original = (InetAddress[]) chain.proceed();
                // ponytail: cached preferences only; no Binder or disk IO in the resolver hook.
                Rule snapshot = rule;
                return snapshot.selected ? AddressPolicy.apply(original, snapshot.mode) : original;
            });
            if ("dev.ipv6ctrl.probe".equals(pkg)) {
                var marker = param.getClassLoader().loadClass("dev.ipv6ctrl.probe.ProbeActivity")
                        .getDeclaredMethod("moduleStatus");
                hook(marker).intercept(chain -> {
                    Rule snapshot = rule;
                    return "api=" + getApiVersion() + ";mode=" + snapshot.mode + ";selected=" + snapshot.selected;
                });
            }
            Log.i("IPV6Ctrl", "Resolver hook installed: " + pkg);
        } catch (Throwable error) {
            rule = new Rule(AddressPolicy.Mode.SYSTEM, false);
            Log.e("IPV6Ctrl", "Resolver hook unavailable; using system behavior", error);
        }
    }

    private void reload(String pkg) {
        try {
            rule = new Rule(AddressPolicy.parse(preferences.getString("mode", "SYSTEM")),
                    preferences.getStringSet("targets", Collections.emptySet()).contains(pkg));
        } catch (RuntimeException error) {
            rule = new Rule(AddressPolicy.Mode.SYSTEM, false);
        }
    }
}
