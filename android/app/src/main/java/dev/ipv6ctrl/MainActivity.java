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
import android.graphics.drawable.RippleDrawable;
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
    private record Event(long time, String kind, String title, String detail) {}
    // ponytail: bounded process-session history; no database or invented boot events.
    private static final Deque<Event> events = new ArrayDeque<>();
    private static final long SESSION_STARTED = System.currentTimeMillis();
    private static long seenProbeTime;
    private AddressPolicy.Mode saved = AddressPolicy.Mode.SYSTEM, draft = saved;
    private Set<String> savedTargets = new HashSet<>(), targets = new HashSet<>(), scope = new HashSet<>();
    private long savedAt;
    private int page;
    private boolean dark, busy, loaded;
    private static volatile boolean uncertain;
    private String message = "等待框架连接";
    private String failure;
    private static SystemIpv6.Status systemStatus;
    private static long systemReadAt;
    private LinearLayout root, content;
    private int bg, fg, muted, surface, blue, amber, green, red, line, soft, accentInk;

    @Override public void onCreate(Bundle state) {
        dark = getSharedPreferences("ui",0).getBoolean("dark",
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::back);
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
                        activity.message = "框架连接已断开，实际状态未确认";
                        event("注意","框架连接断开","已保存配置不能代表目标进程实际状态");activity.render();
                    });
                }
            });
        }
        if (service != null) load(state == null); else render();
    }

    @Override protected void onResume() { super.onResume(); rememberProbe();if (root != null) render(); }
    // Native dispatcher handles API 33+; this fallback is for Android 9–12.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { back(); }
    private void back() { if(page!=0){page=0;render();}else finish(); }
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
    private GradientDrawable outlined(int color,int radius,int stroke) {
        GradientDrawable drawable=rounded(color,radius);drawable.setStroke(dp(1),stroke);return drawable;
    }
    private Button button(String label, int id, boolean primary, Runnable action) {
        Button view = new Button(this);view.setText(label);view.setTextSize(14);view.setAllCaps(false);view.setMinHeight(dp(52));view.setMinimumWidth(0);
        view.setStateListAnimator(null);view.setElevation(0);
        view.setPadding(dp(10),dp(8),dp(10),dp(8));view.setId(id);
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{muted,primary?accentInk:fg}));
        var background=new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled},outlined(soft,14,line));
        background.addState(new int[]{},outlined(primary?blue:surface,14,primary?blue:line));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x18000000),background,null));
        view.setOnClickListener(v->action.run());return view;
    }
    private void section(String label) {
        TextView view=text(label,14,fg,true);view.setPadding(0,dp(22),0,dp(10));content.addView(view);
    }
    private void row(String title,String detail,int color) {
        LinearLayout outer=new LinearLayout(this);outer.setBackgroundColor(surface);outer.setPadding(dp(16),dp(14),dp(16),dp(14));
        View stripe=new View(this);stripe.setBackgroundColor(color);outer.addView(stripe,new LinearLayout.LayoutParams(dp(3),dp(48)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(12),0,0,0);
        labels.addView(text(title,16,color,true));TextView description=text(detail,14,muted,false);description.setPadding(0,dp(6),0,0);labels.addView(description);
        outer.addView(labels,new LinearLayout.LayoutParams(0,-2,1));content.addView(outer);divider();
    }
    private void divider() { View v=new View(this);v.setBackgroundColor(line);content.addView(v,new LinearLayout.LayoutParams(-1,dp(1))); }
    private void addButton(Button button) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(6));content.addView(button,p); }
    private void note(String value,int color) { TextView view=text(value,13,color,false);view.setPadding(0,dp(10),0,dp(10));content.addView(view); }
    private void go(int next) { page=next;render(); }
    private IconView icon(String name,String description,Runnable action) {
        IconView view=new IconView(this,name,muted);view.setContentDescription(description);view.setFocusable(true);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);view.setOnClickListener(v->action.run());return view;
    }
    private void theme() { getSharedPreferences("ui",0).edit().putBoolean("dark",!dark).apply();recreate(); }

    private void render() {
        if(isFinishing()||isDestroyed())return;
        bg=Color.parseColor(dark?"#0B0D10":"#F6F7F9");surface=Color.parseColor(dark?"#14171C":"#FFFFFF");
        fg=Color.parseColor(dark?"#ECEEF1":"#111418");muted=Color.parseColor(dark?"#98A1AE":"#5D6572");
        blue=Color.parseColor(dark?"#6B9BFF":"#2563EB");accentInk=dark?bg:Color.WHITE;
        amber=Color.parseColor(dark?"#FACC15":"#A16207");green=Color.parseColor(dark?"#4ADE80":"#15803D");
        red=Color.parseColor(dark?"#F87171":"#B91C1C");line=Color.parseColor(dark?"#252A32":"#E3E6EB");soft=Color.parseColor(dark?"#1C2027":"#EEF0F3");
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){var i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(i.left,i.top,i.right,i.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        if(page!=0){
            LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(4),0,dp(8),0);
            IconView back=icon("back","返回总览",()->go(0));back.setId(R.id.tab_overview);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));
            header.addView(text(page==1?"应用作用范围":page==3?"高级应用策略":"诊断详情",22,fg,true),new LinearLayout.LayoutParams(0,-2,1));
            header.addView(icon("sun","切换明暗主题",this::theme),new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(header);
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(16),dp(16),dp(16));
        scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(page==0)systemOverview();else if(page==1)scopePage();else if(page==3)overview();else diagnostics();
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(16),dp(10),dp(16),dp(14));actions.setBackground(outlined(bg,0,line));
        boolean appPage=page==1||page==3;
        Button refresh=button(busy?"正在读取…":appPage?"读取框架":"读取系统 · Root",R.id.refresh_status,false,()->{if(appPage)load(false);else systemAction("status");});refresh.setEnabled(!busy&&(!appPage||!uncertain));actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.leftMargin=dp(10);
        Button probe=button("联网检测",R.id.run_probe,false,this::runProbe);probe.setEnabled(!busy);actions.addView(probe,second);
        root.addView(actions);setContentView(root);root.requestApplyInsets();
    }

    private String systemTitle() {
        if(systemStatus==null)return "系统状态未确认";
        return switch(systemStatus.state()){case "blocked"->"已禁用 IPv6";case "enabled"->"IPv6 已启用";default->"部分接口启用 IPv6";};
    }
    private String systemDetail() {
        if(systemStatus==null)return "点击读取系统以请求 Root 并核对实际接口。";
        return "上次读取："+DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(systemReadAt))
                +"\n禁用项 "+systemStatus.disabled()+" / "+systemStatus.total()+"（含 all / default）"
                +"\n"+(systemStatus.restorable()?"本次启动的修改前快照可恢复":"没有本应用可恢复的快照")
                +"\n独立服务："+switch(systemStatus.daemon()){case "running"->"运行中";case "stopped"->"未运行，请检查模块并重启设备";default->"未安装 Runtime 模块";}
                +"\n持续保持："+(systemStatus.managed()?"已请求接管":"未接管")+" · 上次巡检："+switch(systemStatus.lastSweep()){case "ok"->"通过";case "idle"->"待命";case "write-error","snapshot-error"->"失败，请核对";default->"未确认";};
    }
    private void systemOverview() {
        note("IPv6 控制 · 系统级 Root",blue);
        TextView title=text(systemTitle(),32,fg,true);title.setId(R.id.module_status);content.addView(title);
        note(systemDetail(),muted);
        note("安装 Runtime 模块后，独立服务每 3 秒检查各接口并修正漂移；退出 App 后继续保持，无需应用作用域。",fg);
        Button block=button(busy?"处理中…":"禁用并持续保持 IPv6",R.id.block_system,true,()->new AlertDialog.Builder(this)
                .setTitle("禁用并持续保持 IPv6？").setMessage("需要已安装并运行的 Runtime 模块。先保存原参数，再禁用并交给独立服务逐接口保持。影响全设备及已有连接，IPv6-only 网络可能断网。恢复会先停止接管再恢复快照。仅本次开机有效；巡检之间仍有窗口，切网后请检测。")
                .setNegativeButton("取消",null).setPositiveButton("保存快照并保持",(d,w)->systemAction("block")).show());
        block.setEnabled(!busy);addButton(block);
        Button restore=button("恢复修改前系统配置",R.id.restore_system,false,()->systemAction("restore"));
        restore.setEnabled(!busy);addButton(restore);
        Button pause=button("停止持续保持（不恢复参数）",R.id.pause_system,false,()->systemAction("pause"));
        pause.setEnabled(!busy);addButton(pause);
        String badge=probeBadge();row("外网 IPv6",badge,badge.equals("IPv6 可联网")?green:amber);
        addButton(button("诊断详情",R.id.tab_diagnostics,false,()->go(2)));
        addButton(button("高级：应用解析策略（可选）",R.id.tab_scope,false,()->go(3)));
        if(loaded&&saved!=AddressPolicy.Mode.SYSTEM&&!savedTargets.isEmpty())note("仍有已保存的应用解析策略，可在高级入口恢复跟随系统并重启目标。",amber);
        showFailure();timeline();
        note("独立服务巡检本机参数，不会后台请求联网 API。重启后默认不接管；API 失败不等于系统阻断成功。",muted);
    }
    private void systemAction(String action) {
        if(busy)return;
        event("指令",action.equals("block")?"请求系统级阻断并保持":action.equals("restore")?"请求恢复系统快照":action.equals("pause")?"请求停止持续保持":"请求读取系统状态","Root · 不申请应用作用域");
        work(()->{
            systemStatus=null;
            if(!action.equals("status"))getSharedPreferences("ui",0).edit().putLong("system_changed_at",System.currentTimeMillis()).apply();
            SystemIpv6.Status result=SystemIpv6.run(this,action);
            systemStatus=result;systemReadAt=System.currentTimeMillis();
            if(!action.equals("status"))getSharedPreferences("ui",0).edit().putLong("system_changed_at",systemReadAt).apply();
            event("确认",action.equals("status")?"已读取系统参数与服务状态":action.equals("block")?"系统参数已核对并请求持续接管":action.equals("pause")?"已停止接管，参数未恢复":"已停止接管并恢复修改前参数",systemTitle()+" · 请手动联网检测");
        });
    }

    private void overview() {
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setPadding(dp(16),dp(6),dp(16),dp(16));hero.setBackground(outlined(surface,20,line));content.addView(hero);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("IPv6 控制 · LSPosed",13,muted,true),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout badge=new LinearLayout(this);badge.setGravity(Gravity.CENTER_VERTICAL);badge.setPadding(dp(8),dp(5),dp(8),dp(5));badge.setBackground(outlined(surface,99,line));
        int stateColor=uncertain?amber:connected()?green:muted;
        badge.addView(new IconView(this,"shield",stateColor),new LinearLayout.LayoutParams(dp(16),dp(16)));
        TextView status=text(busy?"读取中":uncertain?"待核对":connected()?"已连接":"未连接",12,stateColor,true);status.setId(R.id.module_status);status.setPadding(dp(4),0,0,0);badge.addView(status);top.addView(badge);
        top.addView(icon("sun","切换明暗主题",this::theme),new LinearLayout.LayoutParams(dp(48),dp(48)));hero.addView(top);
        TextView title=text(loaded?shortLabel(saved):"等待连接",34,fg,true);title.setPadding(0,dp(8),0,0);title.setAlpha(busy?.55f:1);hero.addView(title);
        TextView caption=text(loaded?(uncertain?"上次读取的策略 · 保存待核对":"已保存的应用解析策略"):"尚未读取框架配置",12,muted,false);caption.setPadding(0,dp(4),0,dp(12));hero.addView(caption);
        LinearLayout segment=new LinearLayout(this);segment.setPadding(dp(4),dp(4),dp(4),dp(4));segment.setBackground(rounded(soft,14));
        int[] ids={R.id.policy_system,R.id.policy_ipv4_first,R.id.policy_ipv4_only};int index=0;
        for(AddressPolicy.Mode mode:AddressPolicy.Mode.values()){
            boolean selected=draft==mode;RadioButton choice=new RadioButton(this);choice.setId(ids[index++]);choice.setButtonDrawable((android.graphics.drawable.Drawable)null);
            choice.setText((selected?"✓ ":"")+shortLabel(mode));choice.setTextSize(12);choice.setGravity(Gravity.CENTER);choice.setChecked(selected);choice.setPadding(dp(3),dp(8),dp(3),dp(8));choice.setMinHeight(dp(52));
            choice.setTextColor(selected?accentInk:muted);choice.setBackground(rounded(selected?blue:soft,11));choice.setEnabled(connected()&&!busy);choice.setAlpha(choice.isEnabled()?1:.5f);
            choice.setOnClickListener(v->{draft=mode;render();});segment.addView(choice,new LinearLayout.LayoutParams(0,-2,1));
        }
        hero.addView(segment);
        TextView detail=text(dirty()?"待保存："+label(draft):description(saved),12,dirty()?amber:muted,false);detail.setPadding(0,dp(10),0,dp(12));hero.addView(detail);
        LinearLayout cells=new LinearLayout(this);
        metric(cells,"已存目标",loaded?savedTargets.size()+" 个":"未读取",()->go(1));
        metric(cells,"框架",connected()?"API 102":"未连接",this::openManager);
        metric(cells,"上次探针",probeBadge(),()->go(2));hero.addView(cells);
        Button save=button(busy?"正在同步…":"保存并应用",R.id.save_policy,true,this::confirmSave);save.setEnabled(connected()&&!busy&&dirty());
        LinearLayout.LayoutParams saveLayout=new LinearLayout.LayoutParams(-1,-2);saveLayout.topMargin=dp(14);hero.addView(save,saveLayout);
        LinearLayout links=new LinearLayout(this);LinearLayout.LayoutParams linkLayout=new LinearLayout.LayoutParams(-1,-2);linkLayout.topMargin=dp(10);content.addView(links,linkLayout);
        links.addView(button("作用范围",R.id.tab_scope,false,()->go(1)),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=dp(8);links.addView(button("诊断详情",R.id.tab_diagnostics,false,()->go(2)),right);
        if(!connected()) {
            note(uncertain?message:"在 LSPosed 中启用模块并勾选独立探针，再完全重开管理应用。",amber);
            addButton(button("打开 LSPosed",View.NO_ID,true,this::openManager));
        }
        showFailure();timeline();
        note("仅控制作用域内的 Java 解析；不代表系统 IPv6 状态。",muted);
    }

    private void metric(LinearLayout parent,String name,String value,Runnable action) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(8),dp(10),dp(8));box.setBackground(rounded(soft,12));
        box.addView(text(name,11,muted,false));TextView result=text(value,13,fg,true);result.setSingleLine(true);result.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(result);
        box.setContentDescription(name+"，"+value);box.setFocusable(true);box.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(parent.getChildCount()>0)p.leftMargin=dp(8);parent.addView(box,p);
    }
    private void showFailure() {
        if(failure==null)return;
        LinearLayout alert=new LinearLayout(this);alert.setOrientation(LinearLayout.VERTICAL);alert.setPadding(dp(14),dp(12),dp(14),dp(12));alert.setBackground(outlined(surface,14,red));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);content.addView(alert,p);
        alert.addView(text("操作未完成",14,red,true));TextView detail=text(failure,13,fg,false);detail.setPadding(0,dp(6),0,dp(8));alert.addView(detail);
        LinearLayout actions=new LinearLayout(this);alert.addView(actions);
        boolean appPage=page==1||page==3;
        if(!uncertain||!appPage){Button retry=button("重新读取",View.NO_ID,false,()->{if(appPage)load(false);else systemAction("status");});retry.setEnabled(!busy);actions.addView(retry,new LinearLayout.LayoutParams(0,-2,1));}
        actions.addView(button("收起提示",View.NO_ID,false,()->{failure=null;render();}),new LinearLayout.LayoutParams(0,-2,1));
    }
    private static void event(String kind,String title,String detail) {
        synchronized(events){events.addFirst(new Event(System.currentTimeMillis(),kind,title,detail));while(events.size()>40)events.removeLast();}
    }
    private void timeline() {
        List<Event> snapshot;synchronized(events){snapshot=new ArrayList<>(events);}
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(4),dp(22),dp(4),dp(10));
        heading.addView(text("操作时间线",14,fg,true),new LinearLayout.LayoutParams(0,-2,1));heading.addView(text("本次会话 "+snapshot.size()+" 条",12,muted,false));content.addView(heading);
        if(snapshot.isEmpty())note("暂无记录。刷新后显示实际读取结果。",muted);
        for(Event e:snapshot){
            int color=switch(e.kind){case "错误"->red;case "注意"->amber;case "指令"->blue;case "确认","检测"->green;default->muted;};
            LinearLayout item=new LinearLayout(this);content.addView(item);
            FrameLayout rail=new FrameLayout(this);item.addView(rail,new LinearLayout.LayoutParams(dp(18),-1));
            View track=new View(this);track.setBackgroundColor(line);FrameLayout.LayoutParams trackP=new FrameLayout.LayoutParams(dp(2),-1,Gravity.CENTER_HORIZONTAL);rail.addView(track,trackP);
            View dot=new View(this);GradientDrawable circle=outlined(bg,99,color);circle.setStroke(dp(2),color);dot.setBackground(circle);FrameLayout.LayoutParams dotP=new FrameLayout.LayoutParams(dp(10),dp(10),Gravity.TOP|Gravity.CENTER_HORIZONTAL);dotP.topMargin=dp(13);rail.addView(dot,dotP);
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(8),dp(6),0,dp(12));item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            TextView stamp=text(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(e.time))+"   "+e.kind,11,color,false);stamp.setTypeface(Typeface.MONOSPACE);labels.addView(stamp);
            labels.addView(text(e.title,14,fg,true));if(!e.detail.isEmpty())labels.addView(text(e.detail,12,muted,false));
        }
    }

    private void scopePage() {
        note("先选择目标，再请求 LSPosed 作用域，最后保存配置。新加入的目标需完全重启。",muted);
        note(message,connected()?muted:amber);showFailure();
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
        note("系统级控制不需要这里的授权。仅在确有按应用需求时选择目标；新目标需要重启进程。",muted);
    }

    private void diagnostics() {
        showFailure();
        row("系统参数",systemTitle()+"\n"+systemDetail(),blue);
        row("框架服务",service==null?"未连接，不能确认模块激活":message,connected()?green:amber);
        row("已保存策略",loaded?label(saved)+" · "+savedTargets.size()+" 个目标":"尚未读取",blue);
        section("独立进程实测");TextView result=text(probeSummary(),14,fg,false);result.setId(R.id.probe_result);result.setPadding(dp(20),dp(12),dp(20),dp(12));result.setTextIsSelectable(true);content.addView(result);
        Button local=button("仅检测本机解析（不联网）",View.NO_ID,false,()->runProbe(false));local.setEnabled(!busy);addButton(local);
        Button reset=button("清除应用解析策略",R.id.reset_policy,false,()->{draft=AddressPolicy.Mode.SYSTEM;confirmSave();});reset.setEnabled(connected()&&!busy);addButton(reset);
        addButton(button("导出脱敏诊断",View.NO_ID,false,()->{
            String report="IPV6 Ctrl 2.1-dev\n"+systemTitle()+"\n"+systemDetail()+"\n应用策略="+saved+"\n目标数="+savedTargets.size()+"\n"+probeSummary();
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"分享前请复核诊断内容"));
        }));
        addButton(button("开源许可",View.NO_ID,false,()->{
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(getAssets().open("licenses.txt"),java.nio.charset.StandardCharsets.UTF_8))){
                StringBuilder all=new StringBuilder();String next;while((next=reader.readLine())!=null)all.append(next).append('\n');notice(all.toString());
            }catch(Exception e){notice("许可文件读取失败");}
        }));
        note("联网检测仅代表当前默认网络到 ipify 的路径；VPN、代理、切网及 API 故障可能影响结果。每次手动检测各请求一次 IPv4 / IPv6 API，无自动重试，不保存公网 IP。",muted);
    }

    private String label(AddressPolicy.Mode mode) { return switch(mode){case SYSTEM->"跟随系统";case IPV4_FIRST->"优先使用 IPv4";case IPV4_ONLY->"过滤 IPv6 解析结果";}; }
    private String shortLabel(AddressPolicy.Mode mode) { return switch(mode){case SYSTEM->"跟随系统";case IPV4_FIRST->"IPv4 优先";case IPV4_ONLY->"过滤 IPv6";}; }
    private String description(AddressPolicy.Mode mode) { return switch(mode){case SYSTEM->"保持所选应用的原有解析行为。";case IPV4_FIRST->"IPv4 排在前面，保留 IPv6 备用。";case IPV4_ONLY->"过滤所选应用的 Java IPv6 解析结果。";}; }
    private String probeBadge() {
        SharedPreferences ui=getSharedPreferences("ui",0);String raw=ui.getString("probe_report",null);
        if(raw==null)return "未检测";
        try{
            JSONObject report=new JSONObject(raw);String hook=report.optString("hook");
            if(report.optLong("testedAt",ui.getLong("probe_time",0))<Math.max(savedAt,ui.getLong("system_changed_at",0)))return "需重测";
            if(report.optBoolean("online")){
                if(report.optBoolean("onlineV6"))return "IPv6 可联网";
                if(hook.contains("mode=IPV4_ONLY;selected=true"))return "受应用策略影响";
                return report.optBoolean("onlineV4")?"IPv6 未连通":"网络 / API 未确认";
            }
            return "未联网检测";
        }catch(Exception e){return "记录损坏";}
    }
    private void rememberProbe() {
        SharedPreferences ui=getSharedPreferences("ui",0);long at=ui.getLong("probe_time",0);
        if(at<=seenProbeTime)return;seenProbeTime=at;
        if(at<SESSION_STARTED)return;
        String result=probeBadge();boolean bad=result.equals("记录损坏");
        event(bad?"错误":result.equals("IPv6 可联网")?"检测":"注意","已读取探针报告",result+" · 完整结果见诊断");
    }
    private void runProbe() {
        runProbe(true);
    }
    private void runProbe(boolean online) {
        try{
            startActivity(new Intent().setClassName(PROBE,PROBE+".ProbeActivity").putExtra("online",online));
            event("指令","启动独立探针",online?"ipify IPv4 / IPv6 各一次 HTTPS 查询；不保存公网 IP":"仅解析回环地址，不联网");
        }catch(Exception e){event("错误","探针启动失败","请安装同版本、同签名的探针 APK");notice("请先安装同版本的探针 APK。");render();}
    }
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
        if(uncertain){failure="保存结果尚未确认，请完全关闭应用后重开核对。";render();return;}
        XposedService bound=service;if(bound==null){
            failure="框架未连接。请在 LSPosed 中启用模块，再完全重开管理应用。";
            event("注意","无法读取框架配置","当前框架服务未连接");render();return;
        }
        work(()->{
            if(bound.getApiVersion()<102){service=null;throw new IllegalStateException("当前框架 API 低于 102");}
            SharedPreferences prefs=bound.getRemotePreferences("policy");
            saved=AddressPolicy.parse(prefs.getString("mode","SYSTEM"));savedTargets=new HashSet<>(prefs.getStringSet("targets",Collections.emptySet()));savedAt=prefs.getLong("saved_at",0);
            scope=new HashSet<>(bound.getScope());if(resetDraft){draft=saved;targets=new HashSet<>(savedTargets);}
            message=bound.getFrameworkName()+" "+bound.getFrameworkVersion()+" · 配置已读取";
            loaded=true;event("读取","已读取框架配置",label(saved)+" · "+savedTargets.size()+" 个目标");
        });
    }
    private void work(Runnable job) {
        if(busy)return;busy=true;failure=null;render();IO.execute(()->{
            try{job.run();}catch(RuntimeException e){failure="操作未完成："+e.getMessage();event("错误","操作未完成",String.valueOf(e.getMessage()));}
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
        event("指令","请求保存策略",label(next)+" · "+nextTargets.size()+" 个目标");
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
                uncertain=true;message="保存状态未确认。请完全关闭应用后重开核对配置；暂不能判断实际状态。";failure=message;
                event("错误","保存结果未确认","框架没有可靠确认，需重开应用核对");return;
            }
            saved=next;savedTargets=nextTargets;savedAt=timestamp;scope=granted;message="配置已保存；新作用域需重启目标。请运行探针核验。";
            loaded=true;event("确认","框架已确认保存",label(next)+" · 实际效果需探针核验");
        });
    }
    private void requestScope() {
        if(!connected()||busy)return;
        List<String> requested=new ArrayList<>(targets);
        if(requested.isEmpty()){notice("请先选择目标");return;}
        event("指令","请求 LSPosed 作用域",requested.size()+" 个所选目标");render();
        try{service.requestScope(requested,new XposedService.OnScopeEventListener(){
            @Override public void onScopeRequestApproved(List<String> approved){runOnUiThread(()->{event("确认","框架已批准作用域",approved.size()+" 个目标 · 新目标需重启进程");load(false);});}
            @Override public void onScopeRequestFailed(String reason){runOnUiThread(()->{event("注意","作用域请求未通过",reason);notice("作用域未授权："+reason);render();});}
        });}catch(RuntimeException e){event("错误","作用域请求未完成","请检查框架连接");notice("作用域请求未完成，请检查框架连接。");render();}
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
            String title=hook.startsWith("api=102;")?"应用解析 Hook：已注入":"应用解析 Hook：未注入（系统检测无需注入）";
            boolean stale=report.optLong("testedAt",ui.getLong("probe_time",0))<Math.max(savedAt,ui.getLong("system_changed_at",0));
            String online=report.optBoolean("online")?"\n外网 IPv4："+(report.optBoolean("onlineV4")?"HTTPS 可用":report.optString("onlineV4Detail","未确认"))
                    +"\n外网 IPv6："+(report.optBoolean("onlineV6")?"HTTPS 可用":report.optString("onlineV6Detail","未确认"))
                    +"\n"+(hook.contains("mode=IPV4_ONLY;selected=true")?"探针受应用过滤策略影响，失败不能判断系统状态。":"IPv6 API 失败可能是网络或服务故障，需结合系统参数判断。")
                    :"\n本次未联网检测。";
            return (stale?"旧结果，请重新检测\n":"")+title+"\n检测时间："+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(ui.getLong("probe_time",0)))+online
                    +(report.has("error")?"\n本机解析异常："+report.optString("error"):"")
                    +"\n回环 IPv4："+(report.optBoolean("ipv4")?"可解析":"失败")+"\n回环 IPv6："+(report.optBoolean("ipv6")?"可解析":"未返回地址")
                    +"\n返回地址：IPv4 "+report.optInt("v4Count")+" / IPv6 "+report.optInt("v6Count")+"\n首选地址：IPv"+report.optInt("firstFamily")
                    +"\n系统参数状态请看上方读取结果。";
        }catch(Exception e){return "诊断记录损坏，请重新运行探针。";}
    }
}
