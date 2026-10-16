package dev.ipv6ctrl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String PROBE = "dev.ipv6ctrl.probe";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile XposedService service;
    private static boolean registered;
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private AddressPolicy.Mode saved = AddressPolicy.Mode.SYSTEM, draft = saved;
    private Set<String> savedTargets = new HashSet<>(), targets = new HashSet<>(), scope = new HashSet<>();
    private long savedAt;
    private int page;
    private boolean dark, busy, uncertain;
    private String message = "等待框架连接";
    private LinearLayout root, content;
    private int bg, fg, muted, surface, blue, purple, amber, green, red, line;

    @Override public void onCreate(Bundle state) {
        dark = getSharedPreferences("ui",0).getBoolean("dark",
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if (state != null) {
            page = state.getInt("page");
            draft = AddressPolicy.parse(state.getString("draft"));
            if(state.getStringArrayList("targets")!=null)targets.addAll(state.getStringArrayList("targets"));
        }
        current = new WeakReference<>(this);
        if (!registered) {
            registered = true;
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(XposedService bound) {
                    service = bound;
                    MainActivity activity = current.get();
                    if (activity != null) activity.runOnUiThread(() -> activity.load(true));
                }
                @Override public void onServiceDied(XposedService dead) {
                    if (service == dead) service = null;
                    MainActivity activity = current.get();
                    if (activity != null) activity.runOnUiThread(() -> {
                        activity.message = "框架连接已断开，实际状态未确认"; activity.render();
                    });
                }
            });
        }
        if (service != null) load(state == null); else render();
    }

    @Override protected void onResume() { super.onResume(); if (root != null) render(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page",page);state.putString("draft",draft.name());state.putStringArrayList("targets",new ArrayList<>(targets));
    }
    @Override protected void onDestroy() { if(current.get()==this)current.clear();super.onDestroy(); }

    private boolean connected() { return service != null && !uncertain; }
    private boolean dirty() { return draft != saved || !targets.equals(savedTargets); }
    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density*value+.5f); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);
        if(bold)view.setTypeface(null,Typeface.BOLD);
        return view;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;
    }
    private Button button(String label, int id, boolean primary, Runnable action) {
        Button view = new Button(this);view.setText(label);view.setTextSize(14);view.setAllCaps(false);view.setMinHeight(dp(48));
        view.setId(id);view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{muted,primary?Color.WHITE:blue}));
        view.setBackgroundTintList(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{line,primary?blue:surface}));
        view.setOnClickListener(v->action.run());return view;
    }
    private void section(String label) {
        TextView view=text(label,14,muted,true);view.setPadding(dp(20),dp(16),dp(20),dp(10));content.addView(view);
    }
    private void row(String title,String detail,int color) {
        LinearLayout outer=new LinearLayout(this);outer.setBackgroundColor(surface);outer.setPadding(dp(16),dp(14),dp(16),dp(14));
        View stripe=new View(this);stripe.setBackgroundColor(color);outer.addView(stripe,new LinearLayout.LayoutParams(dp(3),dp(48)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(1);labels.setPadding(dp(12),0,0,0);
        labels.addView(text(title,16,color,true));TextView description=text(detail,14,muted,false);description.setPadding(0,dp(6),0,0);labels.addView(description);
        outer.addView(labels,new LinearLayout.LayoutParams(0,-2,1));content.addView(outer);divider();
    }
    private void divider() { View v=new View(this);v.setBackgroundColor(line);content.addView(v,new LinearLayout.LayoutParams(-1,dp(1))); }
    private void addButton(Button button) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(16),dp(6),dp(16),dp(6));content.addView(button,p); }
    private void note(String value,int color) { TextView view=text(value,14,color,false);view.setPadding(dp(20),dp(12),dp(20),dp(12));content.addView(view); }

    private void render() {
        if(isFinishing()||isDestroyed())return;
        bg=Color.parseColor(dark?"#0B111E":"#F4F6FB");surface=Color.parseColor(dark?"#111827":"#FFFFFF");
        fg=Color.parseColor(dark?"#F8FAFC":"#0F172A");muted=Color.parseColor(dark?"#94A3B8":"#475569");
        blue=Color.parseColor(dark?"#3B82F6":"#2563EB");purple=Color.parseColor(dark?"#C084FC":"#7C3AED");
        amber=Color.parseColor(dark?"#FBBF24":"#B45309");green=Color.parseColor(dark?"#34D399":"#047857");
        red=Color.parseColor(dark?"#FCA5A5":"#B91C1C");line=Color.parseColor(dark?"#1E293B":"#E2E8F0");
        root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(bg);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){var i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(i.left,i.top,i.right,i.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),dp(10),dp(12),dp(10));header.setBackgroundColor(surface);
        header.addView(text(new String[]{"总览","作用范围","诊断"}[page],26,fg,true),new LinearLayout.LayoutParams(0,dp(48),1));
        IconView theme=new IconView(this,"sun",muted);theme.setContentDescription("切换明暗主题");theme.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        theme.setOnClickListener(v->{getSharedPreferences("ui",0).edit().putBoolean("dark",!dark).apply();recreate();});header.addView(theme,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(1);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView status=text(busy?"正在同步…":connected()?"模块已连接 · API 102":"模块未连接",14,connected()?green:amber,true);
        status.setId(R.id.module_status);status.setPadding(dp(20),dp(12),dp(20),dp(8));content.addView(status);
        if(page==0)overview();else if(page==1)scopePage();else diagnostics();
        LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(surface);
        String[] names={"总览","作用范围","诊断"},icons={"clock","scope","pulse"};int[] ids={R.id.tab_overview,R.id.tab_scope,R.id.tab_diagnostics};
        for(int i=0;i<3;i++){
            final int targetPage=i;LinearLayout item=new LinearLayout(this);item.setId(ids[i]);item.setOrientation(1);item.setGravity(Gravity.CENTER);item.setMinimumHeight(dp(64));
            if(page==i)item.setBackground(rounded(dark?0xff172554:0xffeff6ff,24));item.setContentDescription(names[i]);item.setOnClickListener(v->{page=targetPage;render();});
            item.addView(new IconView(this,icons[i],page==i?blue:muted),new LinearLayout.LayoutParams(dp(24),dp(24)));
            TextView caption=text(names[i],12,page==i?blue:muted,page==i);caption.setGravity(Gravity.CENTER);item.addView(caption);nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        }
        root.addView(nav);setContentView(root);root.requestApplyInsets();
    }

    private void overview() {
        row("目标策略："+label(draft),dirty()?"有未保存变更":connected()?"配置已读取；新目标需重启进程":"等待框架读取配置",dirty()?purple:blue);
        row("模块结果",message,connected()?green:amber);
        String probe=probeSummary();int newline=probe.indexOf('\n');
        row("独立探针",newline<0?probe:probe.substring(0,newline)+" · 完整结果见诊断",muted);
        if(!connected()) {
            section("激活指引");note("1. 在 LSPosed 中启用 IPV6 Ctrl\n2. 勾选独立探针作为测试目标\n3. 重启目标应用，返回此处检查连接",muted);
            addButton(button("打开 LSPosed",View.NO_ID,true,this::openManager));
        }
        section("IPv6 控制策略"+(dirty()?" · 未保存":""));
        AddressPolicy.Mode[] modes=AddressPolicy.Mode.values();
        String[] details={"保持应用原有解析行为。","将 IPv4 排到前面，保留 IPv6 备用。","过滤 Java 解析结果中的 IPv6；可能导致 IPv6-only 服务不可用。"};
        int[] ids={R.id.policy_system,R.id.policy_ipv4_first,R.id.policy_ipv4_only};
        for(int i=0;i<modes.length;i++){
            AddressPolicy.Mode mode=modes[i];RadioButton choice=new RadioButton(this);choice.setId(ids[i]);choice.setText(label(mode)+"\n"+details[i]);choice.setTextColor(fg);choice.setTextSize(15);
            choice.setPadding(dp(16),dp(14),dp(16),dp(14));choice.setMinHeight(dp(80));choice.setChecked(draft==mode);choice.setButtonTintList(ColorStateList.valueOf(blue));choice.setEnabled(connected()&&!busy);
            choice.setOnClickListener(v->{draft=mode;render();});content.addView(choice);divider();
        }
        note("所选目标 "+targets.size()+" 个。仅影响作用域内的 Java 解析，不拦截 Native 直连或系统底层流量。",muted);
        addButton(button("保存并应用策略",R.id.save_policy,true,this::confirmSave));
        content.getChildAt(content.getChildCount()-1).setEnabled(connected()&&!busy&&dirty());
        Button reset=button("恢复默认策略",R.id.reset_policy,false,()->{draft=AddressPolicy.Mode.SYSTEM;confirmSave();});reset.setEnabled(connected()&&!busy);addButton(reset);
    }

    private void scopePage() {
        note("先选择目标，再请求 LSPosed 作用域，最后保存配置。新加入的目标需完全重启。",muted);
        section("当前目标");Set<String> displayed=new LinkedHashSet<>();displayed.add(PROBE);displayed.addAll(targets);
        for(String pkg:displayed){
            CheckBox check=new CheckBox(this);check.setText(appLabel(pkg)+"\n"+(scope.contains(pkg)?"LSPosed 已授权":"尚未确认作用域"));check.setTextSize(15);check.setTextColor(fg);
            check.setEnabled(!busy);
            check.setPadding(dp(16),dp(12),dp(16),dp(12));check.setMinHeight(dp(72));check.setChecked(targets.contains(pkg));if(PROBE.equals(pkg))check.setId(R.id.target_probe);
            check.setOnCheckedChangeListener((v,on)->{if(on)targets.add(pkg);else targets.remove(pkg);});content.addView(check);divider();
        }
        addButton(button("选择其他应用",View.NO_ID,false,this::pickApps));
        Button request=button("请求所选 LSPosed 作用域",View.NO_ID,true,this::requestScope);request.setEnabled(connected()&&!busy);addButton(request);
        Button save=button("保存目标与策略",R.id.save_policy,false,this::confirmSave);save.setEnabled(connected()&&!busy);addButton(save);
        note("首版排除系统应用和管理器。探针只解析回环地址，不访问互联网。",muted);
    }

    private void diagnostics() {
        row("框架服务",service==null?"未连接，不能确认模块激活":message,connected()?green:amber);
        row("已保存策略",label(saved)+" · "+savedTargets.size()+" 个目标",blue);
        section("独立进程实测");TextView result=text(probeSummary(),14,fg,false);result.setId(R.id.probe_result);result.setPadding(dp(20),dp(12),dp(20),dp(12));result.setTextIsSelectable(true);content.addView(result);
        addButton(button("运行独立探针",R.id.run_probe,true,()->{
            try{startActivity(new Intent().setClassName(PROBE,PROBE+".ProbeActivity"));}
            catch(Exception e){notice("请先安装同版本的探针 APK。");}
        }));
        addButton(button("刷新框架与配置",View.NO_ID,false,()->load(false)));
        addButton(button("导出脱敏诊断",View.NO_ID,false,()->{
            String report="IPV6 Ctrl 0.1.0\n策略="+saved+"\n目标数="+savedTargets.size()+"\n"+probeSummary();
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"分享前请复核诊断内容"));
        }));
        addButton(button("开源许可",View.NO_ID,false,()->{
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(getAssets().open("licenses.txt"),java.nio.charset.StandardCharsets.UTF_8))){
                StringBuilder all=new StringBuilder();String next;while((next=reader.readLine())!=null)all.append(next).append('\n');notice(all.toString());
            }catch(Exception e){notice("许可文件读取失败");}
        }));
        note("实测仅代表独立探针的 Java 地址解析。其他应用、已有连接与 Native 网络栈仍需分别验证。",muted);
    }

    private String label(AddressPolicy.Mode mode) { return switch(mode){case SYSTEM->"跟随系统";case IPV4_FIRST->"优先使用 IPv4";case IPV4_ONLY->"过滤 IPv6 解析结果";}; }
    private String appLabel(String pkg) {
        try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}
        catch(Exception e){return pkg.equals(PROBE)?"独立探针（尚未安装）":pkg;}
    }
    private void notice(String value) { new AlertDialog.Builder(this).setTitle("IPV6 Ctrl").setMessage(value).setPositiveButton("知道了",null).show(); }
    private void openManager() {
        Intent intent=getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        if(intent==null)notice("请从 LSPosed 通知或管理器入口打开模块设置。");else startActivity(intent);
    }
    private void load(boolean resetDraft) {
        XposedService bound=service;if(bound==null){render();return;}
        work(()->{
            if(bound.getApiVersion()<102){service=null;throw new IllegalStateException("当前框架 API 低于 102");}
            SharedPreferences prefs=bound.getRemotePreferences("policy");
            saved=AddressPolicy.parse(prefs.getString("mode","SYSTEM"));savedTargets=new HashSet<>(prefs.getStringSet("targets",Collections.emptySet()));savedAt=prefs.getLong("saved_at",0);
            scope=new HashSet<>(bound.getScope());if(resetDraft){draft=saved;targets=new HashSet<>(savedTargets);}
            message=bound.getFrameworkName()+" "+bound.getFrameworkVersion()+" · 配置已读取";
        });
    }
    private void work(Runnable job) {
        if(busy)return;busy=true;render();IO.execute(()->{
            try{job.run();}catch(RuntimeException e){message="操作未完成："+e.getMessage();}
            runOnUiThread(()->{busy=false;render();});
        });
    }
    private void confirmSave() {
        if(!connected()||busy)return;
        if(draft==AddressPolicy.Mode.IPV4_ONLY)new AlertDialog.Builder(this).setTitle("过滤所选应用的 IPv6 解析？")
                .setMessage("仅影响已选目标的 Java 地址解析。IPv6-only 服务可能不可用。恢复时切回跟随系统，并重启目标应用。")
                .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->save()).show();
        else save();
    }
    private void save() {
        final AddressPolicy.Mode next=draft;final Set<String> nextTargets=new HashSet<>(targets);final XposedService bound=service;
        work(()->{
            if(bound==null)throw new IllegalStateException("框架未连接");
            Set<String> granted=new HashSet<>(bound.getScope());
            if(next!=AddressPolicy.Mode.SYSTEM&&!granted.containsAll(nextTargets))throw new IllegalStateException("部分目标尚未获得 LSPosed 作用域，请先请求授权");
            if(next!=AddressPolicy.Mode.SYSTEM&&nextTargets.isEmpty())throw new IllegalStateException("请先选择至少一个目标");
            long timestamp=System.currentTimeMillis();
            try {
                if(!bound.getRemotePreferences("policy").edit().putString("mode",next.name()).putStringSet("targets",nextTargets).putLong("saved_at",timestamp).commit())throw new IllegalStateException("框架没有确认保存");
            }catch(RuntimeException e){
                // A lost Binder acknowledgement cannot prove whether the remote write happened.
                uncertain=true;message="保存状态未确认。请完全关闭应用后重开核对配置；暂不能判断实际状态。";return;
            }
            saved=next;savedTargets=nextTargets;savedAt=timestamp;scope=granted;message="配置已保存；新作用域需重启目标。请运行探针核验。";
        });
    }
    private void requestScope() {
        if(!connected()||busy)return;
        List<String> requested=new ArrayList<>(targets);
        if(requested.isEmpty()){notice("请先选择目标");return;}
        try{service.requestScope(requested,new XposedService.OnScopeEventListener(){
            @Override public void onScopeRequestApproved(List<String> approved){runOnUiThread(()->load(false));}
            @Override public void onScopeRequestFailed(String reason){runOnUiThread(()->notice("作用域未授权："+reason));}
        });}catch(RuntimeException e){notice("作用域请求未完成，请检查框架连接。");}
    }
    private void pickApps() {
        Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String,String> apps=new TreeMap<>();
        for(var info:getPackageManager().queryIntentActivities(launcher,0)){
            ApplicationInfo app=info.activityInfo.applicationInfo;String pkg=app.packageName;
            if((app.flags&ApplicationInfo.FLAG_SYSTEM)==0&&!pkg.equals(getPackageName())&&!pkg.equals("org.lsposed.manager"))apps.put(pkg,info.loadLabel(getPackageManager()).toString());
        }
        List<String> packages=new ArrayList<>(apps.keySet());String[] names=new String[packages.size()];boolean[] checked=new boolean[names.length];Set<String> chosen=new HashSet<>(targets);
        for(int i=0;i<names.length;i++){names[i]=apps.get(packages.get(i));checked[i]=chosen.contains(packages.get(i));}
        new AlertDialog.Builder(this).setTitle("选择普通应用").setMultiChoiceItems(names,checked,(dialog,index,on)->{if(on)chosen.add(packages.get(index));else chosen.remove(packages.get(index));})
                .setNegativeButton("取消",null).setPositiveButton("确定",(dialog,which)->{targets=chosen;render();}).show();
    }
    private String probeSummary() {
        SharedPreferences ui=getSharedPreferences("ui",0);String raw=ui.getString("probe_report",null);
        if(raw==null)return "未检测。请在诊断页运行独立探针。";
        try{
            JSONObject report=new JSONObject(raw);String hook=report.optString("hook");
            String title=hook.startsWith("api=102;")?"已确认探针注入":"探针未注入";
            if(report.has("error"))return "探针失败："+report.getString("error");
            boolean stale=ui.getLong("probe_time",0)<savedAt;
            return (stale?"旧结果，请重新检测\n":"")+title+"\n检测时间："+DateFormat.getTimeInstance().format(new Date(ui.getLong("probe_time",0)))
                    +"\nIPv4："+(report.optBoolean("ipv4")?"可解析":"失败")+"\nIPv6："+(report.optBoolean("ipv6")?"可解析":"被过滤")
                    +"\n返回地址：IPv4 "+report.optInt("v4Count")+" / IPv6 "+report.optInt("v6Count")+"\n首选地址：IPv"+report.optInt("firstFamily")
                    +"\n仅代表探针；实际系统 IPv6 状态未确认。";
        }catch(Exception e){return "诊断记录损坏，请重新运行探针。";}
    }
}
