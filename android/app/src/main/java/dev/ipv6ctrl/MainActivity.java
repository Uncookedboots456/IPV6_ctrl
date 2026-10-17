package dev.ipv6ctrl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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

public final class MainActivity extends Activity {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private record Event(long time, String kind, String title, String detail) {}
    // ponytail: bounded process-session history and results; no persistent probe reports.
    private static final Deque<Event> events = new ArrayDeque<>();
    private static volatile boolean busy;
    private static volatile String failure;
    private static SystemIpv6.Status systemStatus;
    private static long systemReadAt, onlineStartedAt, onlineReadAt;
    private static OnlineCheck.Result onlineV4, onlineV6;
    private int page;
    private boolean dark;
    private LinearLayout root, content;
    private int bg, fg, muted, surface, blue, amber, green, red, line, soft, accentInk;

    @Override public void onCreate(Bundle state) {
        dark=getSharedPreferences("ui",0).getBoolean("dark",
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::back);
        if(state!=null)page=state.getInt("page")==2?2:0;
        current=new WeakReference<>(this);
        render();
    }
    @Override protected void onResume() { super.onResume();if(root!=null)render(); }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { back(); }
    private void back() { if(page!=0){page=0;render();}else finish(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state);state.putInt("page",page); }
    @Override protected void onDestroy() { if(current.get()==this)current.clear();super.onDestroy(); }

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
            header.addView(text("诊断详情",22,fg,true),new LinearLayout.LayoutParams(0,-2,1));
            header.addView(icon("sun","切换明暗主题",this::theme),new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(header);
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(16),dp(16),dp(16));
        scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(page==0)systemOverview();else diagnostics();
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(16),dp(10),dp(16),dp(14));actions.setBackground(outlined(bg,0,line));
        Button refresh=button(busy?"处理中…":"读取系统 · Root",R.id.refresh_status,false,()->systemAction("status"));refresh.setEnabled(!busy);actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.leftMargin=dp(10);
        Button online=button(busy?"处理中…":"联网检测",R.id.run_online,false,this::runOnline);online.setEnabled(!busy);actions.addView(online,second);
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
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("IPv6 控制 · 系统级 Root",14,blue,true),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(icon("sun","切换明暗主题",this::theme),new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(heading);
        TextView title=text(systemTitle(),32,fg,true);title.setId(R.id.module_status);content.addView(title);
        note(systemDetail(),muted);
        note("安装 Runtime 模块后，独立服务每 3 秒检查各接口并修正漂移；退出客户端后继续保持。",fg);
        Button block=button(busy?"处理中…":"禁用并持续保持 IPv6",R.id.block_system,true,()->new AlertDialog.Builder(this)
                .setTitle("禁用并持续保持 IPv6？").setMessage("需要已安装并运行的 Runtime 模块。先保存原参数，再禁用并交给独立服务逐接口保持。影响全设备及已有连接，IPv6-only 网络可能断网。恢复会先停止接管再恢复快照。仅本次开机有效；巡检之间仍有窗口，切网后请检测。")
                .setNegativeButton("取消",null).setPositiveButton("保存快照并保持",(d,w)->systemAction("block")).show());
        block.setEnabled(!busy);addButton(block);
        Button restore=button("恢复修改前系统配置",R.id.restore_system,false,()->systemAction("restore"));
        restore.setEnabled(!busy);addButton(restore);
        Button pause=button("停止持续保持（不恢复参数）",R.id.pause_system,false,()->systemAction("pause"));
        pause.setEnabled(!busy);addButton(pause);
        String badge=onlineBadge();row("外网 IPv6",badge,badge.equals("IPv6 可联网")?green:amber);
        addButton(button("诊断详情",R.id.tab_diagnostics,false,()->go(2)));
        showFailure();timeline();
        note("独立服务巡检本机参数，不会后台请求联网 API。重启后默认不接管；API 失败不等于系统阻断成功。",muted);
    }
    private void systemAction(String action) {
        if(busy)return;
        event("指令",action.equals("block")?"请求系统级阻断并保持":action.equals("restore")?"请求恢复系统快照":action.equals("pause")?"请求停止持续保持":"请求读取系统状态","Root · 系统参数");
        work(()->{
            systemStatus=null;
            if(!action.equals("status"))getSharedPreferences("ui",0).edit().putLong("system_changed_at",System.currentTimeMillis()).apply();
            SystemIpv6.Status result=SystemIpv6.run(getApplicationContext(),action);
            systemStatus=result;systemReadAt=System.currentTimeMillis();
            if(!action.equals("status"))getSharedPreferences("ui",0).edit().putLong("system_changed_at",systemReadAt).apply();
            event("确认",action.equals("status")?"已读取系统参数与服务状态":action.equals("block")?"系统参数已核对并请求持续接管":action.equals("pause")?"已停止接管，参数未恢复":"已停止接管并恢复修改前参数",systemTitle()+" · 请手动联网检测");
        });
    }

    private void showFailure() {
        if(failure==null)return;
        LinearLayout alert=new LinearLayout(this);alert.setOrientation(LinearLayout.VERTICAL);alert.setPadding(dp(14),dp(12),dp(14),dp(12));alert.setBackground(outlined(surface,14,red));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);content.addView(alert,p);
        alert.addView(text("操作未完成",14,red,true));TextView detail=text(failure,13,fg,false);detail.setPadding(0,dp(6),0,dp(8));alert.addView(detail);
        LinearLayout actions=new LinearLayout(this);alert.addView(actions);
        Button retry=button("重新读取",View.NO_ID,false,()->systemAction("status"));retry.setEnabled(!busy);actions.addView(retry,new LinearLayout.LayoutParams(0,-2,1));
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

    private void diagnostics() {
        showFailure();
        row("系统参数",systemTitle()+"\n"+systemDetail(),blue);
        section("客户端联网检测");
        TextView result=text(onlineSummary(),14,fg,false);result.setId(R.id.online_result);
        result.setPadding(dp(16),dp(12),dp(16),dp(12));result.setTextIsSelectable(true);content.addView(result);
        note("每次手动向 ipify 各请求一次 IPv4 / IPv6 HTTPS API，不自动重试，不保存公网 IP。结果只代表检测时客户端到 API 的路径；VPN、切网、DNS 和 API 故障会影响结果。切网后请重测，失败不能单独证明系统阻断成功。",muted);
        addButton(button("导出脱敏诊断",View.NO_ID,false,()->{
            String report="IPV6 Ctrl "+BuildConfig.VERSION_NAME+"\n"+systemTitle()+"\n"+systemDetail()+"\n"+onlineSummary();
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"分享前请复核诊断内容"));
        }));
        addButton(button("开源许可",View.NO_ID,false,()->{
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(getAssets().open("licenses.txt"),java.nio.charset.StandardCharsets.UTF_8))){
                StringBuilder all=new StringBuilder();String next;while((next=reader.readLine())!=null)all.append(next).append('\n');notice(all.toString());
            }catch(Exception e){notice("许可文件读取失败");}
        }));
    }
    private String onlineBadge() {
        if(onlineReadAt==0)return "未检测";
        if(onlineStartedAt<getSharedPreferences("ui",0).getLong("system_changed_at",0))return "需重测";
        if(onlineV6.reachable())return "IPv6 可联网";
        return onlineV4.reachable()?"IPv6 未连通":"网络 / API 未确认";
    }
    private String onlineSummary() {
        if(onlineReadAt==0)return "尚未检测，点击下方“联网检测”。";
        return onlineBadge()+"\n检测时间："+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(onlineReadAt))
                +"\n外网 IPv4："+onlineV4.detail()+"\n外网 IPv6："+onlineV6.detail()
                +"\n失败原因需结合系统参数与实际网络判断。";
    }
    private void runOnline() {
        if(busy)return;
        event("指令","请求客户端联网检测","ipify IPv4 / IPv6 各一次 HTTPS 查询");
        work(()->{
            onlineReadAt=0;onlineStartedAt=System.currentTimeMillis();
            OnlineCheck.Result v4=OnlineCheck.query(4),v6=OnlineCheck.query(6);
            onlineV4=v4;onlineV6=v6;onlineReadAt=System.currentTimeMillis();
            event(v6.reachable()?"检测":"注意","客户端联网检测完成",onlineBadge()+" · 完整结果见诊断");
        });
    }
    private void notice(String value) { new AlertDialog.Builder(this).setTitle("IPV6 Ctrl").setMessage(value).setPositiveButton("知道了",null).show(); }
    private void work(Runnable job) {
        if(busy)return;busy=true;failure=null;render();IO.execute(()->{
            try{job.run();}catch(RuntimeException e){failure="操作未完成："+e.getMessage();event("错误","操作未完成",String.valueOf(e.getMessage()));}
            MainActivity activity=current.get();
            if(activity!=null)activity.runOnUiThread(()->{
                busy=false;MainActivity shown=current.get();if(shown!=null)shown.render();
            });else busy=false;
        });
    }
}
