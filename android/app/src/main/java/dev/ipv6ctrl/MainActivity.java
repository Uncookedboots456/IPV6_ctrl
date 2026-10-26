package dev.ipv6ctrl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    // Two bounded network tasks: one per address family, independent of the serial Root executor.
    private static final ExecutorService NET = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object ONLINE_LOCK = new Object();
    private static final Object NETWORK_LOCK = new Object();
    private static final AtomicBoolean onlineRunning = new AtomicBoolean();
    private static final AtomicInteger onlineRemaining = new AtomicInteger();
    private static final AtomicInteger onlineAttempt = new AtomicInteger();
    private static final AtomicInteger networkGeneration = new AtomicInteger();
    private static final Runnable NETWORK_RETEST = MainActivity::maybeAutoCheck;
    private static final Runnable STATUS_EXPIRY = MainActivity::renderCurrent;
    private static final Runnable ONLINE_EXPIRY = MainActivity::updateOnlineUi;
    private static final long STATUS_FADE_MS = 180L;
    private static final long ONLINE_UI_DEADLINE_MS = 15_000L;
    private static final long NETWORK_DEBOUNCE_MS = 700L;
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private record Event(long time, String kind, String title, String detail) {}
    private record Family(OnlineCheck.Result result, boolean done, boolean discarded) {
        static Family pending() { return new Family(null, false, false); }
    }
    private record OnlineSnapshot(int attempt, int generation, long systemChanges, long startedElapsed,
                                  Family v4, Family v6, boolean finished, boolean timedOut,
                                  long finishedElapsed, long finishedWall) {}
    // ponytail: bounded process-session history and results; no persistent probe reports.
    private static final Deque<Event> events = new ArrayDeque<>();
    private static volatile String operation = "";
    private static volatile String failure;
    private static volatile long seenReadingElapsed;
    private static volatile OnlineSnapshot online;
    private static volatile Network observedNetwork;
    private static boolean networkSeen;
    private boolean resumed, opened;
    private ConnectivityManager.NetworkCallback networkCallback;
    private int page;
    private boolean dark;
    private LinearLayout root, content;
    private ScrollView scroll;
    private int renderedPage = -1;
    private int pendingScroll;
    private boolean scrollReady;
    private View networkDetect, networkProgress, heroView;
    private TextView networkV4, networkV6, networkHint;
    private String lastStatusSignature;
    private int bg, fg, muted, surface, blue, amber, green, red, line, soft, accentInk;

    @Override public void onCreate(Bundle state) {
        dark=getSharedPreferences("ui",0).getBoolean("dark",
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::back);
        if(state!=null){page=state.getInt("page")==2?2:0;pendingScroll=Math.max(0,state.getInt("scroll",0));}
        current=new WeakReference<>(this);
        render();
    }
    @Override protected void onStart() {
        super.onStart();
        if(!isChangingConfigurations())opened=true;
        registerNetwork();
    }
    @Override protected void onStop() { unregisterNetwork();super.onStop(); }
    @Override protected void onResume() {
        super.onResume();resumed=true;syncSystemReading();render();
        boolean launch=opened;opened=false;
        ensureStatusRead(launch);
        if(launch)startOnlineCheck(true);else maybeAutoCheck();
    }
    @Override protected void onPause() {
        resumed=false;MAIN.removeCallbacks(NETWORK_RETEST);MAIN.removeCallbacks(STATUS_EXPIRY);MAIN.removeCallbacks(ONLINE_EXPIRY);super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);if(focused&&root!=null&&syncSystemReading())render();
    }
    @Override protected void onDestroy() {
        unregisterNetwork();
        if(current.get()==this){
            MAIN.removeCallbacks(NETWORK_RETEST);MAIN.removeCallbacks(STATUS_EXPIRY);MAIN.removeCallbacks(ONLINE_EXPIRY);current.clear();
        }
        super.onDestroy();
    }
    private boolean syncSystemReading() {
        boolean changed=false;
        SystemIpv6.Reading reading=SystemIpv6.latest;
        if(reading.elapsed()>seenReadingElapsed){seenReadingElapsed=reading.elapsed();failure=reading.error();changed=true;}
        String tileError=getSharedPreferences("ui",0).getString("tile_error",null);
        if(tileError!=null){failure=tileError;getSharedPreferences("ui",0).edit().remove("tile_error").apply();changed=true;}
        return changed;
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { back(); }
    private void back() { if(page!=0){page=0;render();}else finish(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);state.putInt("page",page);
        state.putInt("scroll",scroll==null||!scrollReady?pendingScroll:scroll.getScrollY());
    }

    private static boolean busy() { return !operation.isEmpty(); }
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
        view.setBackground(buttonBackground(primary,14));
        view.setOnClickListener(v->action.run());return view;
    }
    private RippleDrawable buttonBackground(boolean primary,int radius) {
        var background=new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled},outlined(soft,radius,line));
        background.addState(new int[]{},outlined(primary?blue:surface,radius,primary?blue:line));
        return new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x18000000),background,null);
    }
    private void divider() { View v=new View(this);v.setBackgroundColor(line);content.addView(v,new LinearLayout.LayoutParams(-1,dp(1))); }
    private void addButton(Button button) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(6));content.addView(button,p); }
    private void note(String value,int color) { TextView view=text(value,13,color,false);view.setPadding(0,dp(10),0,dp(10));content.addView(view); }
    private void go(int next) { page=next;render(); }
    private IconView icon(String name,String description,Runnable action) {
        IconView view=new IconView(this,name,muted);view.setContentDescription(description);view.setFocusable(true);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);view.setOnClickListener(v->action.run());return view;
    }
    private void theme() {
        dark=!dark;
        getSharedPreferences("ui",0).edit().putBoolean("dark",dark).apply();
        render();
    }

    private void render() {
        if(isFinishing()||isDestroyed())return;
        String signature=statusSignature();
        boolean fadeStatus=resumed&&lastStatusSignature!=null&&!signature.equals(lastStatusSignature)&&animationsEnabled();
        lastStatusSignature=signature;
        pendingScroll=ClientRules.scrollToRestore(scroll!=null,renderedPage==page,scrollReady,
                scroll==null?0:scroll.getScrollY(),pendingScroll);
        int keepScroll=pendingScroll;
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
        scroll=new ScrollView(this);scroll.setFillViewport(true);scrollReady=false;
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(16),dp(16),dp(16));
        scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        heroView=null;
        if(page==0)systemOverview();else diagnostics();
        setContentView(root);root.requestApplyInsets();
        renderedPage=page;
        restoreScroll(scroll,keepScroll);
        scheduleExpiry();
        if(fadeStatus){
            View status=page==0?heroView:findViewById(R.id.module_status);
            if(status!=null){status.setAlpha(0f);status.animate().alpha(1f).setDuration(STATUS_FADE_MS).start();}
        }
    }
    private void restoreScroll(ScrollView target,int y) {
        // scrollTo before layout clamps to 0; one callback per tree, and an old tree must not scroll a newer one.
        target.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){
                if(target.getViewTreeObserver().isAlive())target.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if(scroll!=target)return;
                scrollReady=true;
                if(y>0)target.scrollTo(0,y);
                pendingScroll=target.getScrollY();
            }
        });
    }
    private void scheduleExpiry() {
        MAIN.removeCallbacks(STATUS_EXPIRY);MAIN.removeCallbacks(ONLINE_EXPIRY);
        if(!resumed)return;
        SystemIpv6.Reading reading=SystemIpv6.latest;
        SystemIpv6.Status status=reading.status();
        long now=SystemClock.elapsedRealtime();
        if(status!=null){
            long age=now-reading.elapsed();
            long remaining=ClientRules.STATUS_FRESH_MS-age;
            if(age>=0&&remaining>0){
                long sweep=ClientRules.sweepAgeNow(now,reading.elapsed(),status.lastSweepAge());
                if(sweep>=0&&"ok".equals(status.lastSweep())){
                    long sweepRemaining=ClientRules.SWEEP_FRESH_MS-sweep;
                    if(sweepRemaining>0&&sweepRemaining<remaining)remaining=sweepRemaining;
                }
                MAIN.postDelayed(STATUS_EXPIRY,remaining+50);
            }
        }
        OnlineSnapshot snapshot=online;
        if(snapshot!=null&&snapshot.finished()&&!snapshot.timedOut()){
            long remaining=ClientRules.ONLINE_FRESH_MS-(now-snapshot.finishedElapsed());
            if(remaining>0)MAIN.postDelayed(ONLINE_EXPIRY,remaining+50);
        }
    }
    private boolean animationsEnabled() {
        return ValueAnimator.areAnimatorsEnabled()
                &&Settings.Global.getFloat(getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;
    }
    private String statusSignature() {
        SystemIpv6.Reading reading=SystemIpv6.latest;SystemIpv6.Status status=reading.status();
        if(status==null)return "none|"+reading.error();
        return status.state()+"|"+status.disabled()+"|"+status.total()+"|"+status.restorable()+"|"+status.daemon()
                +"|"+status.managed()+"|"+status.lastSweep()+"|"+status.desired()+"|"+status.bootApply()
                +"|"+health()+"|"+(statusFresh()?"fresh":"stale");
    }

    private Button quietButton(String label,int id,Runnable action) {
        Button view=button(label,id,false,action);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x18000000),rounded(Color.TRANSPARENT,12),null));
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{muted,blue}));return view;
    }
    private void loading(String label,int id) {
        LinearLayout loading=new LinearLayout(this);loading.setId(id);loading.setGravity(Gravity.CENTER_VERTICAL);
        loading.setPadding(0,dp(12),0,dp(12));loading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ProgressBar spinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(blue));spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        loading.addView(spinner,new LinearLayout.LayoutParams(dp(24),dp(24)));
        TextView caption=text(label,14,muted,false);caption.setPadding(dp(12),0,0,0);loading.addView(caption);content.addView(loading);
    }
    private static String progressLabel() {
        return switch(operation){
            case "block"->"正在开启纯 IPv4 模式…";case "lock"->"正在锁定纯 IPv4 模式…";
            case "restore"->"正在关闭纯 IPv4 模式…";case "enable"->"正在关闭纯 IPv4 模式…";case "pause"->"正在解除锁定…";
            default->"正在读取状态…";
        };
    }
    private void rootProgress() {
        if(busy())loading(progressLabel(),R.id.system_loading);
    }

    private static SystemIpv6.Status systemStatus() { return SystemIpv6.latest.status(); }
    private static boolean statusFresh() {
        SystemIpv6.Reading reading=SystemIpv6.latest;
        return reading.status()!=null&&ClientRules.isFresh(SystemClock.elapsedRealtime(),reading.elapsed(),ClientRules.STATUS_FRESH_MS);
    }
    private static String health() {
        SystemIpv6.Reading reading=SystemIpv6.latest;SystemIpv6.Status status=reading.status();
        long sweep=status==null?-1:ClientRules.sweepAgeNow(SystemClock.elapsedRealtime(),reading.elapsed(),status.lastSweepAge());
        return ClientRules.health(status==null?null:status.state(),status==null?null:status.daemon(),
                status!=null&&status.managed(),status==null?null:status.desired(),
                status==null?null:status.bootApply(),status==null?null:status.lastSweep(),statusFresh(),sweep);
    }
    private static String formatTime(long wallTime) {
        return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(wallTime));
    }
    private static String sweepLabel(String sweep) {
        return switch(sweep){
            case "ok"->"通过";case "idle"->"待命";case "write-error","snapshot-error"->"失败，请核对";
            case "boot-apply-failed"->"本次开机自动应用失败";case "boot-apply-interrupted"->"本次开机自动应用被中断";
            default->"未确认";
        };
    }
    private static String bootApplyLabel(String bootApply) {
        return switch(bootApply){
            case "applied"->"已应用";case "failed"->"失败";case "interrupted"->"被中断";
            case "manual"->"未自动应用（已手动锁定）";
            case "none"->"未尝试";default->"未知";
        };
    }
    private static String lockTargetLabel(SystemIpv6.Status status) {
        return switch(ClientRules.lockTarget(status.desired(),status.lockSupported())){
            case ClientRules.LOCK_LOCKED->"已设置（重启后自动接管，退出 App 仍保持）";
            case ClientRules.LOCK_NONE->"未设置（本次启用只到重启）";
            case ClientRules.LOCK_UNSUPPORTED->"Runtime 过旧，无法确认；请先更新模块，不会按仅本次处理";
            default->"未知（不视为已锁定或已解锁）";
        };
    }
    private String sweepConfirmText() {
        SystemIpv6.Reading reading=SystemIpv6.latest;SystemIpv6.Status status=reading.status();
        if(status==null)return "";
        if(!statusFresh())return "状态读取已过期，不能确认当前巡检";
        if(!"ok".equals(status.lastSweep()))
            return "上次巡检："+sweepLabel(status.lastSweep())+"（状态读取 "+formatTime(reading.wallTime())+"）";
        long sweep=ClientRules.sweepAgeNow(SystemClock.elapsedRealtime(),reading.elapsed(),status.lastSweepAge());
        if(sweep<0)return "上次巡检确认时间未知，不能确认当前持续健康";
        long confirmed=reading.wallTime()-sweep;
        return "上次巡检确认 "+formatTime(confirmed)+"（"+Math.max(0,sweep/1000)+" 秒前）"
                +(sweep>ClientRules.SWEEP_FRESH_MS?" · 已超出确认窗口":"");
    }
    private int lockTargetColor() {
        SystemIpv6.Status status=systemStatus();
        if(status==null||!status.lockSupported())return amber;
        if("failed".equals(status.bootApply())||"interrupted".equals(status.bootApply()))return red;
        if(!ClientRules.LOCK_NONE.equals(ClientRules.lockTarget(status.desired(),true))){
            if(ClientRules.LOCK_UNKNOWN.equals(ClientRules.lockTarget(status.desired(),true))
                    ||"unknown".equals(status.bootApply()))return amber;
        }
        return muted;
    }
    private static boolean lockRequested(SystemIpv6.Status status) {
        return status!=null&&status.state().equals("blocked")&&status.managed()
                &&ClientRules.LOCK_LOCKED.equals(ClientRules.lockTarget(status.desired(),status.lockSupported()))
                &&!"failed".equals(status.bootApply())&&!"interrupted".equals(status.bootApply());
    }
    private String systemTitle() {
        SystemIpv6.Status status=systemStatus();
        if(status==null)return "系统状态未确认";
        if(!statusFresh())return "系统状态待确认";
        return switch(status.state()){case "blocked"->"已禁用 IPv6";case "enabled"->"IPv6 已启用";default->"部分接口启用 IPv6";};
    }
    private String systemDetail() {
        SystemIpv6.Reading reading=SystemIpv6.latest;SystemIpv6.Status status=reading.status();
        if(status==null)return reading.error()!=null
                ?"上次读取失败："+reading.error()+"\n点“刷新状态”重试。"
                :"点“刷新状态”读取系统参数；已授权后打开应用会自动读取。";
        return "状态读取："+formatTime(reading.wallTime())
                +(statusFresh()?"":"（已过期，请刷新）")
                +"\n禁用项 "+status.disabled()+" / "+status.total()+"（含 all / default）"
                +"\n"+(status.restorable()?"本次启动的修改前快照可恢复":"没有本应用可恢复的快照")
                +"\n独立服务："+switch(status.daemon()){case "running"->"运行中";case "stopped"->"未运行，请检查模块并重启设备";case "missing"->"未安装 Runtime 模块";default->"未确认";}
                +"\n跨重启锁定："+lockTargetLabel(status)
                +"\n本次开机自动应用："+bootApplyLabel(status.bootApply())
                +"\n本次接管："+(status.managed()?"已请求接管":"未接管")
                +"\n"+sweepConfirmText();
    }
    private boolean rootReady() { return getSharedPreferences("ui",0).getBoolean("root_ready",false); }
    private static int tint(int color,int alpha) { return (color&0x00FFFFFF)|(alpha<<24); }
    private static boolean locked(SystemIpv6.Status status) {
        return status!=null&&ClientRules.LOCK_LOCKED.equals(ClientRules.lockTarget(status.desired(),status.lockSupported()));
    }
    private static boolean ipv4Active(SystemIpv6.Status status) {
        return status!=null&&("blocked".equals(status.state())||status.managed());
    }
    // One colour per state: green = held and confirmed, amber = needs a look, red = not doing what it says.
    private int heroColor() {
        if(busy())return blue;
        SystemIpv6.Status status=systemStatus();
        if(status==null)return SystemIpv6.latest.error()!=null?red:muted;
        return switch(health()){
            case ClientRules.HEALTH_CONFIRMED->green;
            case ClientRules.HEALTH_FAILED,ClientRules.HEALTH_INTERRUPTED,ClientRules.HEALTH_BOOT_FAILED->red;
            case ClientRules.HEALTH_PENDING,ClientRules.HEALTH_UNMANAGED->amber;
            case ClientRules.HEALTH_OTHER->"enabled".equals(status.state())?blue:amber;
            default->muted;
        };
    }
    private String heroIcon() {
        SystemIpv6.Status status=systemStatus();
        if(status==null)return "clock";
        return switch(health()){
            case ClientRules.HEALTH_CONFIRMED->"shield-check";
            case ClientRules.HEALTH_FAILED,ClientRules.HEALTH_INTERRUPTED,ClientRules.HEALTH_BOOT_FAILED->"shield-alert";
            default->switch(status.state()){case "blocked"->"shield";case "enabled"->"globe";default->"clock";};
        };
    }
    private String heroTitle() {
        SystemIpv6.Status status=systemStatus();
        if(status==null)return rootReady()?"状态未知":"需要 Root 授权";
        return switch(status.state()){case "blocked"->"纯 IPv4 模式";case "enabled"->"IPv6 已开启";default->"部分开启 IPv6";};
    }
    private String heroDetail() {
        if(busy())return progressLabel();
        SystemIpv6.Reading reading=SystemIpv6.latest;SystemIpv6.Status status=reading.status();
        if(status==null){
            if(reading.error()!=null)return "读取失败 · 点按重试";
            return rootReady()?"点按读取系统状态":"点按授权 Root 后即可切换";
        }
        if(!statusFresh())return "状态已过期 · 点按右上角刷新";
        boolean locked=locked(status);
        return switch(health()){
            case ClientRules.HEALTH_CONFIRMED->locked?"已锁定 · 重启后自动保持":"保持中 · 仅本次开机";
            case ClientRules.HEALTH_PENDING->(locked?"已锁定":"保持中")+" · 等待巡检确认";
            case ClientRules.HEALTH_FAILED->"保持异常 · 查看诊断";
            case ClientRules.HEALTH_BOOT_FAILED->"开机自动锁定未生效";
            case ClientRules.HEALTH_INTERRUPTED->"后台服务已停止 · 未在保持";
            case ClientRules.HEALTH_UNMANAGED->"未持续保持 · 可能被改回";
            case ClientRules.HEALTH_OTHER->"enabled".equals(status.state())?"纯 IPv4 已关闭 · 双栈网络"
                    :"已关闭 "+status.disabled()+" / "+status.total()+" 项";
            default->"状态待确认 · 点按右上角刷新";
        };
    }
    // Only what blocks the switches; everything else lives in 诊断.
    private String guidance() {
        SystemIpv6.Status status=systemStatus();
        if(status==null||lockRequested(status))return null;
        if(!status.lockSupported())return "Root 模块版本过旧，请更新模块并重启";
        if("missing".equals(status.daemon()))return "未安装 Root 模块，暂时无法开启纯 IPv4";
        if(!"running".equals(status.daemon()))return "Root 模块未运行，请重启设备后再试";
        return null;
    }
    private void toggleIpv4Mode() {
        if(busy())return;
        SystemIpv6.Status status=systemStatus();
        if(status==null){systemAction("status");return;}
        boolean active=ipv4Active(status);
        boolean locked=locked(status);
        if(active){
            systemAction(status.restorable()?"restore":"enable");
        }else if(locked){
            systemAction("lock");
        }else if(ClientRules.blockEnabled(false,status.state(),status.daemon(),status.managed())){
            systemAction("block");
        }
    }
    private void toggleLockTarget() {
        if(busy())return;
        SystemIpv6.Status status=systemStatus();
        if(status==null)return;
        boolean locked=locked(status);
        boolean bootFailed="failed".equals(status.bootApply())||"interrupted".equals(status.bootApply());
        if(locked&&!bootFailed){
            systemAction("pause");
        }else if(ClientRules.lockEnabled(false,status.state(),status.daemon(),status.managed(),
                status.desired(),status.bootApply(),status.lockSupported())){
            systemAction("lock");
        }
    }
    private void switchRow(LinearLayout box,String title,String subtitle,boolean checked,boolean enabled,int id,Runnable action) {
        if(box.getChildCount()>0){
            View line=new View(this);line.setBackgroundColor(this.line);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.setMarginStart(dp(16));box.addView(line,p);
        }
        LinearLayout row=new LinearLayout(this);row.setId(id);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16),dp(14),dp(16),dp(14));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x18000000),null,rounded(Color.WHITE,16)));
        row.setEnabled(enabled);
        row.setContentDescription(title+"，"+(checked?"开启":"关闭")+"，"+subtitle);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,16,enabled?fg:muted,true));
        TextView sub=text(subtitle,12,muted,false);sub.setPadding(0,dp(3),0,0);labels.addView(sub);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this);toggle.setChecked(checked);toggle.setEnabled(enabled);
        toggle.setClickable(false);toggle.setFocusable(false);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled},{android.R.attr.state_checked},{}},
                new int[]{muted,blue,muted}));
        toggle.setTrackTintList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled},{android.R.attr.state_checked},{}},
                new int[]{tint(muted,0x28),tint(blue,0x55),tint(muted,0x38)}));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);sp.setMarginStart(dp(12));
        row.addView(toggle,sp);
        row.setOnClickListener(v->{if(enabled)action.run();});
        box.addView(row);
    }
    private void systemOverview() {
        content.setPadding(dp(24),dp(4),dp(24),dp(20));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("IPV6 Ctrl",16,muted,true),new LinearLayout.LayoutParams(0,-2,1));
        IconView refreshTop=icon("refresh","刷新系统状态",()->systemAction("status"));refreshTop.setId(R.id.refresh_status);
        refreshTop.setEnabled(ClientRules.refreshEnabled(busy()));
        heading.addView(refreshTop,new LinearLayout.LayoutParams(dp(48),dp(48)));
        IconView details=icon("pulse","诊断详情",()->go(2));details.setId(R.id.tab_diagnostics);
        heading.addView(details,new LinearLayout.LayoutParams(dp(48),dp(48)));
        heading.addView(icon("sun","切换明暗主题",this::theme),new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(heading);

        SystemIpv6.Status status=systemStatus();
        int color=heroColor();
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0,dp(32),0,dp(24));
        hero.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x14ffffff:0x0c000000),null,rounded(Color.WHITE,24)));
        hero.setContentDescription(heroTitle()+"，"+heroDetail()+"。点按切换纯 IPv4 模式");
        hero.setOnClickListener(v->toggleIpv4Mode());
        FrameLayout ring=new FrameLayout(this);
        GradientDrawable disc=new GradientDrawable();disc.setShape(GradientDrawable.OVAL);
        disc.setColor(tint(color,dark?0x24:0x18));disc.setStroke(dp(2),tint(color,0x55));ring.setBackground(disc);
        IconView glyph=new IconView(this,heroIcon(),color).maxSize(52);
        ring.addView(glyph,new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER));
        hero.addView(ring,new LinearLayout.LayoutParams(dp(120),dp(120)));
        TextView title=text(heroTitle(),28,fg,true);title.setId(R.id.module_status);title.setGravity(Gravity.CENTER);
        title.setPadding(0,dp(18),0,dp(6));hero.addView(title);
        LinearLayout sub=new LinearLayout(this);sub.setGravity(Gravity.CENTER);
        sub.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if(busy()){
            ProgressBar spinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spinner.setId(R.id.system_loading);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(blue));spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(16),dp(16));p.setMarginEnd(dp(8));sub.addView(spinner,p);
        }
        sub.addView(text(heroDetail(),14,busy()||color==blue?muted:color,false));
        hero.addView(sub);
        content.addView(hero,new LinearLayout.LayoutParams(-1,-2));heroView=hero;

        heroControls(status);
        if(failure!=null){
            Button error=quietButton("操作未完成 · 查看详情",R.id.failure_details,()->go(2));error.setTextColor(red);addButton(error);
        }
        String guidance=guidance();
        if(guidance!=null){TextView g=text(guidance,13,amber,false);g.setGravity(Gravity.CENTER);g.setPadding(0,dp(8),0,0);content.addView(g);}
        content.addView(new View(this),new LinearLayout.LayoutParams(-1,dp(24)));
        content.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1)); // keeps 网络连通 at the bottom on tall screens
        networkCheck();
    }
    private void heroControls(SystemIpv6.Status status) {
        if(status==null){
            Button read=button(SystemIpv6.latest.error()!=null?"重试":rootReady()?"读取状态":"授权 Root",View.NO_ID,true,()->systemAction("status"));
            read.setEnabled(ClientRules.refreshEnabled(busy()));pill(read,true);addButton(read);return;
        }
        boolean ipv4On=ipv4Active(status);
        boolean locked=locked(status);
        boolean bootFailed="failed".equals(status.bootApply())||"interrupted".equals(status.bootApply());
        boolean lockOn=locked&&!bootFailed;
        boolean canToggleIpv4=ipv4On?!busy():ClientRules.blockEnabled(busy(),status.state(),status.daemon(),status.managed());
        boolean canToggleLock=lockOn
                ?ClientRules.pauseEnabled(busy(),status.managed(),true)
                :ClientRules.lockEnabled(busy(),status.state(),status.daemon(),status.managed(),
                        status.desired(),status.bootApply(),status.lockSupported());
        String ipv4Sub=ipv4On
                ?(status.managed()?"已停用全设备 IPv6 · 关闭即恢复":"已停用 IPv6（未保持）· 关闭即恢复")
                :"停用全设备 IPv6，仅使用 IPv4";
        String lockSub=!status.lockSupported()?"需更新 Root 模块"
                :bootFailed?"上次开机应用失败 · 开启以重新锁定"
                :lockOn?"重启后自动恢复并持续保持纯 IPv4"
                :"跨重启持久保持纯 IPv4";
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(rounded(surface,16));
        switchRow(card,"纯 IPv4 模式",ipv4Sub,ipv4On,canToggleIpv4,R.id.restore_system,this::toggleIpv4Mode);
        switchRow(card,"锁定",lockSub,lockOn,canToggleLock,R.id.lock_system,this::toggleLockTarget);
        content.addView(card,new LinearLayout.LayoutParams(-1,-2));
    }
    private void pill(Button button,boolean primary) {
        button.setMinHeight(dp(56));button.setTextSize(16);button.setBackground(buttonBackground(primary,28));
    }
    private void networkCheck() {
        OnlineSnapshot snapshot=online;boolean running=onlineRunning.get();
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(0,dp(4),0,0);
        heading.addView(text("网络连通",15,fg,true),new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout slot=new FrameLayout(this);slot.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        IconView refresh=icon("refresh","重新检测网络",this::runOnline);refresh.setId(R.id.run_online);
        ProgressBar spinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spinner.setId(R.id.network_loading);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(blue));spinner.setContentDescription("正在检测网络");
        slot.addView(refresh,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));
        slot.addView(spinner,new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));
        heading.addView(slot,new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(heading);
        networkDetect=refresh;networkProgress=spinner;
        networkV4=networkResult("IPv4");networkV6=networkResult("IPv6");
        networkHint=text("",12,muted,false);networkHint.setPadding(0,dp(8),0,0);content.addView(networkHint);
        updateNetworkUi();
    }
    private TextView networkResult(String family) {
        LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(0,dp(8),0,dp(8));
        item.addView(text(family,15,muted,false),new LinearLayout.LayoutParams(0,-2,1));
        TextView value=text("",15,muted,true);value.setCompoundDrawablePadding(dp(8));item.addView(value);content.addView(item);
        return value;
    }
    private void showNetworkValue(TextView view,Family state) {
        OnlineSnapshot snapshot=online;
        String value;int color;
        if(state==null){value="未检测";color=muted;}
        else if(!state.done()){value="检测中";color=muted;}
        else if(state.discarded()){value=snapshot!=null&&snapshot.timedOut()?"超时":"需重测";color=amber;}
        else if(onlineStale(snapshot)){value="需重测";color=amber;}
        else if(state.result()==null){value="未确认";color=amber;}
        else if(state.result().reachable()){value="可用";color=green;}
        else {value="不通";color=dark?muted:Color.parseColor("#8A929E");}
        view.setText(value);view.setTextColor(color);
        GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(color);dot.setSize(dp(8),dp(8));
        dot.setBounds(0,0,dp(8),dp(8));view.setCompoundDrawablesRelative(dot,null,null,null);
    }
    private static String onlineCaption() {
        OnlineSnapshot snapshot=online;
        if(snapshot==null)return "打开应用时自动检测 · 不保存公网 IP";
        if(!snapshot.finished())return "正在检测…";
        if(snapshot.timedOut())return "检测超时，可稍后重试";
        if(snapshot.generation()!=networkGeneration.get())return "网络已变化，即将重测";
        if(snapshot.systemChanges()!=SystemIpv6.changeCount())return "设置已变化，即将重测";
        if(!ClientRules.isFresh(SystemClock.elapsedRealtime(),snapshot.finishedElapsed(),ClientRules.ONLINE_FRESH_MS))
            return "结果已超过 5 分钟，可重新检测";
        return formatTime(snapshot.finishedWall())+" 检测 · 不保存公网 IP";
    }
    private void updateNetworkUi() {
        if(isFinishing()||isDestroyed())return;
        if(networkDetect==null||networkV4==null||networkV6==null||networkProgress==null||networkHint==null){render();return;}
        OnlineSnapshot snapshot=online;
        boolean running=onlineRunning.get();
        networkDetect.setEnabled(ClientRules.detectEnabled(running));
        networkDetect.setVisibility(running?View.INVISIBLE:View.VISIBLE);
        networkProgress.setVisibility(running?View.VISIBLE:View.INVISIBLE);
        showNetworkValue(networkV4,snapshot==null?null:snapshot.v4());
        showNetworkValue(networkV6,snapshot==null?null:snapshot.v6());
        networkHint.setText(onlineCaption());
        networkHint.setTextColor(onlineNeedsAttention()&&snapshot!=null&&snapshot.finished()?amber:muted);
        TextView detail=findViewById(R.id.online_result);
        if(detail!=null)detail.setText(onlineSummary());
    }
    private static void updateOnlineUi() {
        MainActivity activity=current.get();
        if(activity!=null&&!activity.isFinishing()&&!activity.isDestroyed())activity.updateNetworkUi();
    }
    private static void scheduleCurrentExpiry() {
        MainActivity activity=current.get();
        if(activity!=null&&!activity.isFinishing()&&!activity.isDestroyed())activity.scheduleExpiry();
    }
    private static boolean onlineStale(OnlineSnapshot snapshot) {
        if(snapshot==null)return false;
        if(snapshot.generation()!=networkGeneration.get())return true;
        if(snapshot.systemChanges()!=SystemIpv6.changeCount())return true;
        return snapshot.finished()&&!snapshot.timedOut()
                &&!ClientRules.isFresh(SystemClock.elapsedRealtime(),snapshot.finishedElapsed(),ClientRules.ONLINE_FRESH_MS);
    }
    private static boolean onlineNeedsAttention() {
        OnlineSnapshot snapshot=online;
        return snapshot!=null&&(!snapshot.finished()||snapshot.timedOut()||onlineStale(snapshot));
    }
    private static String onlineBadge() {
        OnlineSnapshot snapshot=online;
        if(snapshot==null)return "未检测";
        if(!snapshot.finished())return "检测中";
        if(snapshot.timedOut())return "检测超时 · 未确认";
        if(onlineStale(snapshot))return "需重测";
        OnlineCheck.Result v6=snapshot.v6().result(),v4=snapshot.v4().result();
        if(v6!=null&&v6.reachable())return "IPv6 可联网";
        if(v4!=null&&v4.reachable())return "IPv6 未连通";
        return "网络 / API 未确认";
    }
    private static String onlineHint() {
        OnlineSnapshot snapshot=online;
        if(snapshot==null)return "打开应用会自动向 ipify 各查询一次 IPv4 / IPv6，不保存公网 IP。";
        if(snapshot.timedOut())return "检测超时：未完成项未确认，迟到结果丢弃；可稍后重新检测。";
        if(!snapshot.finished())return "正在向 ipify 查询 IPv4 / IPv6，两行会分别完成；不保存公网 IP。";
        if(snapshot.generation()!=networkGeneration.get())return "默认网络已变化，旧结果已失效；会自动重测。";
        if(snapshot.systemChanges()!=SystemIpv6.changeCount())return "系统参数已修改，旧结果已失效；会自动重测。";
        if(!ClientRules.isFresh(SystemClock.elapsedRealtime(),snapshot.finishedElapsed(),ClientRules.ONLINE_FRESH_MS))
            return "结果已超过 5 分钟，建议重新检测。";
        return "检测于 "+DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(snapshot.finishedWall()))
                +" · 自动检测不保存公网 IP，切网后失效。";
    }
    private static String familySummary(OnlineSnapshot snapshot,Family family) {
        if(family==null)return "未检测";
        if(!family.done())return "检测中…";
        if(family.discarded())return snapshot.timedOut()?"超时，未确认":"网络或系统已变化，结果丢弃";
        if(family.result()==null)return "未确认";
        return family.result().detail()+(family.result().reachable()?" · 可用":" · 未连通，需结合系统参数判断");
    }
    private String onlineSummary() {
        OnlineSnapshot snapshot=online;
        if(snapshot==null)return "尚未检测。打开应用会自动各查询一次 IPv4 / IPv6。";
        StringBuilder summary=new StringBuilder(onlineBadge());
        if(snapshot.finished()&&snapshot.finishedWall()>0)summary.append("\n检测时间：")
                .append(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(snapshot.finishedWall())));
        summary.append("\n外网 IPv4：").append(familySummary(snapshot,snapshot.v4()));
        summary.append("\n外网 IPv6：").append(familySummary(snapshot,snapshot.v6()));
        summary.append("\n").append(onlineHint());
        summary.append("\n失败原因需结合系统参数与实际网络判断；禁用 IPv6 也不保证 IPv4 可用。");
        return summary.toString();
    }
    private void runOnline() { startOnlineCheck(false); }
    private static void startOnlineCheck(boolean automatic) {
        if(!onlineRunning.compareAndSet(false,true))return;
        int attempt=onlineAttempt.incrementAndGet();
        int generation=networkGeneration.get();
        long systemChanges=SystemIpv6.changeCount();
        long started=SystemClock.elapsedRealtime();
        traceOnline(attempt,"start",started);
        synchronized(ONLINE_LOCK){
            online=new OnlineSnapshot(attempt,generation,systemChanges,started,Family.pending(),Family.pending(),false,false,0,0);
        }
        onlineRemaining.set(2);
        event(automatic?"检测":"指令",automatic?"自动检测当前网络":"请求客户端联网检测",
                "ipify IPv4 / IPv6 各一次 HTTPS 查询，不保存公网 IP");
        NET.execute(()->queryFamily(attempt,4,started));
        NET.execute(()->queryFamily(attempt,6,started));
        MAIN.postDelayed(()->onlineDeadline(attempt),ONLINE_UI_DEADLINE_MS);
        updateOnlineUi();scheduleCurrentExpiry();
    }
    private static void queryFamily(int attempt,int family,long started) {
        String tag=family==4?"v4":"v6";
        traceOnline(attempt,"family="+tag+" start",started);
        OnlineCheck.Result result;
        try{result=OnlineCheck.query(family);}
        catch(RuntimeException error){result=new OnlineCheck.Result(false,error.getClass().getSimpleName());}
        boolean discarded=false,stale=false;
        synchronized(ONLINE_LOCK){
            OnlineSnapshot snapshot=online;
            if(snapshot!=null&&!snapshot.finished()){
                int decision=ClientRules.familyResult(attempt,snapshot.attempt(),snapshot.generation(),networkGeneration.get());
                if(decision==ClientRules.RESULT_IGNORE)discarded=true;
                else{
                    if(decision==ClientRules.RESULT_STALE)stale=true;
                    Family update=decision==ClientRules.RESULT_CURRENT
                            ?new Family(result,true,false):new Family(null,true,true);
                    Family v4=family==4?update:snapshot.v4(),v6=family==6?update:snapshot.v6();
                    boolean both=v4.done()&&v6.done();
                    online=new OnlineSnapshot(snapshot.attempt(),snapshot.generation(),snapshot.systemChanges(),snapshot.startedElapsed(),
                            v4,v6,both,false,both?SystemClock.elapsedRealtime():0,both?System.currentTimeMillis():0);
                }
            }else discarded=true;
        }
        if(discarded)traceOnline(attempt,"family="+tag+" discard",started);
        else if(stale)traceOnline(attempt,"family="+tag+" discard stale",started);
        else traceOnline(attempt,"family="+tag+" finish",started);
        if(onlineRemaining.decrementAndGet()==0)finishOnlineAttempt();
        MAIN.post(MainActivity::updateOnlineUi);
    }
    private static void onlineDeadline(int attempt) {
        boolean timedOut=false;
        long started=0;
        synchronized(ONLINE_LOCK){
            OnlineSnapshot snapshot=online;
            if(snapshot==null||snapshot.attempt()!=attempt||snapshot.finished())return;
            started=snapshot.startedElapsed();
            Family v4=snapshot.v4().done()?snapshot.v4():new Family(null,true,true);
            Family v6=snapshot.v6().done()?snapshot.v6():new Family(null,true,true);
            online=new OnlineSnapshot(snapshot.attempt(),snapshot.generation(),snapshot.systemChanges(),snapshot.startedElapsed(),
                    v4,v6,true,true,SystemClock.elapsedRealtime(),System.currentTimeMillis());
            timedOut=true;
        }
        if(timedOut){
            traceOnline(attempt,"deadline",started);
            event("注意","联网检测超时","未完成的地址族显示未确认，迟到结果丢弃；可稍后重新检测");
            updateOnlineUi();scheduleCurrentExpiry();
        }
    }
    private static void finishOnlineAttempt() {
        MAIN.post(()->{
            onlineRunning.set(false);
            OnlineSnapshot snapshot=online;
            if(snapshot!=null&&snapshot.finished()&&!snapshot.timedOut()){
                traceOnline(snapshot.attempt(),"finish",snapshot.startedElapsed());
                event(snapshot.v6().result()!=null&&snapshot.v6().result().reachable()?"检测":"注意","联网检测完成",onlineBadge());
            }
            updateOnlineUi();scheduleCurrentExpiry();maybeAutoCheck();
        });
    }
    private static void maybeAutoCheck() {
        MainActivity activity=current.get();
        if(activity==null||!activity.resumed)return;
        OnlineSnapshot snapshot=online;
        boolean has=snapshot!=null,finished=has&&snapshot.finished(),timedOut=has&&snapshot.timedOut();
        boolean currentGeneration=has&&snapshot.generation()==networkGeneration.get();
        boolean currentSystem=has&&snapshot.systemChanges()==SystemIpv6.changeCount();
        boolean fresh=has&&ClientRules.isFresh(SystemClock.elapsedRealtime(),snapshot.finishedElapsed(),ClientRules.ONLINE_FRESH_MS);
        if(ClientRules.shouldStartOnlineCheck(onlineRunning.get(),has,finished,timedOut,currentGeneration,currentSystem,fresh))
            startOnlineCheck(true);
    }
    private static void renderCurrent() {
        MainActivity activity=current.get();
        if(activity!=null&&!activity.isFinishing()&&!activity.isDestroyed())activity.render();
    }
    // Fixed attempt/family/phase/elapsed only: never URL, network identity, API response, or credentials.
    private static void traceOnline(int attempt,String phase,long started) {
        if(BuildConfig.DEBUG)android.util.Log.d("IPV6CtrlOnline",
                "attempt="+attempt+" "+phase+" elapsed="+(SystemClock.elapsedRealtime()-started));
    }
    private void registerNetwork() {
        if(networkCallback!=null)return;
        ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(manager==null)return;
        Network active;
        try{active=manager.getActiveNetwork();}catch(RuntimeException error){active=null;}
        // Record the current default first so the registration's initial callback cannot
        // trigger a second cold-start check; a real background network change still differs.
        boolean changed;
        synchronized(NETWORK_LOCK){
            changed=networkSeen&&!Objects.equals(observedNetwork,active);
            observedNetwork=active;networkSeen=true;
        }
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){networkObserved(network);}
            @Override public void onLost(Network network){networkLost(network);}
        };
        try{manager.registerDefaultNetworkCallback(networkCallback);}
        catch(RuntimeException error){networkCallback=null;return;}
        if(changed)networkChanged();
    }
    private void unregisterNetwork() {
        ConnectivityManager.NetworkCallback callback=networkCallback;
        if(callback==null)return;
        networkCallback=null;
        try{((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(callback);}
        catch(RuntimeException ignored){}
    }
    private static void networkObserved(Network network) {
        boolean changed;
        synchronized(NETWORK_LOCK){changed=!Objects.equals(observedNetwork,network);observedNetwork=network;networkSeen=true;}
        if(changed)networkChanged();
    }
    private static void networkLost(Network network) {
        boolean changed;
        synchronized(NETWORK_LOCK){changed=Objects.equals(observedNetwork,network);if(changed)observedNetwork=null;}
        if(changed)networkChanged();
    }
    private static void networkChanged() {
        networkGeneration.incrementAndGet();
        MAIN.removeCallbacks(NETWORK_RETEST);
        MAIN.postDelayed(NETWORK_RETEST,NETWORK_DEBOUNCE_MS);
        MAIN.post(MainActivity::updateOnlineUi);
    }
    private void ensureStatusRead(boolean force) {
        SystemIpv6.Reading reading=SystemIpv6.latest;
        long age=SystemClock.elapsedRealtime()-reading.elapsed();
        boolean rootReady=getSharedPreferences("ui",0).getBoolean("root_ready",false);
        if(force?rootReady&&!busy():ClientRules.shouldAutoRead(rootReady,busy(),reading.status()!=null,reading.error()!=null,age))
            autoStatusRead();
    }
    private void autoStatusRead() {
        work("status",()->{try{SystemIpv6.run(getApplicationContext(),"status");}finally{Ipv6TileService.refreshVisible();}});
    }
    private void systemAction(String action) {
        if(busy())return;
        failure=null;
        event("指令",switch(action){
            case "block"->"请求系统级阻断并保持";case "lock"->"请求启用并锁定纯 IPv4（跨重启）";
            case "restore"->"请求解除锁定并恢复系统快照";case "enable"->"请求解除锁定并开启 IPv6";case "pause"->"请求解除锁定并停止保持（保留参数）";
            default->"请求读取系统状态";
        },"Root · 系统参数");
        work(action,()->{
            try{SystemIpv6.run(getApplicationContext(),action);}
            finally{Ipv6TileService.refreshVisible();}
            event("确认",switch(action){
                case "status"->"已读取系统参数与服务状态";case "block"->"系统参数已核对并请求本次接管";
                case "lock"->"已启用并锁定纯 IPv4，重启后仍会接管";
                case "pause"->"已解除跨重启锁定并停止接管，当前参数保留";
                case "enable"->"已解除锁定并开启 IPv6";
                default->"已解除锁定并恢复修改前参数";
            },systemTitle());
        });
    }

    private void showFailure() {
        if(failure==null)return;
        LinearLayout alert=new LinearLayout(this);alert.setOrientation(LinearLayout.VERTICAL);alert.setPadding(dp(14),dp(12),dp(14),dp(12));alert.setBackground(outlined(surface,14,red));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);content.addView(alert,p);
        alert.addView(text("操作未完成",14,red,true));TextView detail=text(failure,13,fg,false);detail.setPadding(0,dp(6),0,dp(8));alert.addView(detail);
        LinearLayout actions=new LinearLayout(this);alert.addView(actions);
        Button retry=button("重新读取",View.NO_ID,false,()->systemAction("status"));retry.setEnabled(ClientRules.refreshEnabled(busy()));actions.addView(retry,new LinearLayout.LayoutParams(0,-2,1));
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

    private LinearLayout group(String title) {
        TextView label=text(title,13,muted,true);label.setPadding(dp(4),dp(20),0,dp(8));content.addView(label);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackground(rounded(surface,16));
        content.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
    }
    private void kv(LinearLayout box,String label,String value,int color) {
        if(box.getChildCount()>0){
            View line=new View(this);line.setBackgroundColor(this.line);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.setMarginStart(dp(16));box.addView(line,p);
        }
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(13),dp(16),dp(13));
        row.addView(text(label,14,muted,false),new LinearLayout.LayoutParams(-2,-2));
        TextView v=text(value,14,color,false);v.setGravity(Gravity.END);v.setPadding(dp(16),0,0,0);
        row.addView(v,new LinearLayout.LayoutParams(0,-2,1));box.addView(row);
    }
    private void link(LinearLayout box,String label,int id,Runnable action) {
        if(box.getChildCount()>0){
            View line=new View(this);line.setBackgroundColor(this.line);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.setMarginStart(dp(16));box.addView(line,p);
        }
        TextView row=text(label,15,fg,false);row.setId(id);row.setPadding(dp(16),dp(15),dp(16),dp(15));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x18000000),null,rounded(Color.WHITE,16)));
        row.setOnClickListener(v->action.run());box.addView(row);
    }
    private void systemRows(LinearLayout box) {
        SystemIpv6.Reading reading=SystemIpv6.latest;SystemIpv6.Status status=reading.status();
        if(status==null){kv(box,"系统状态",reading.error()!=null?"读取失败":"尚未读取",reading.error()!=null?red:muted);return;}
        boolean fresh=statusFresh();
        kv(box,"IPv6 参数",switch(status.state()){case "blocked"->"已关闭";case "enabled"->"已开启";default->"部分关闭";}
                +" · "+status.disabled()+" / "+status.total()+" 项禁用",fg);
        kv(box,"后台服务",switch(status.daemon()){case "running"->"运行中";case "stopped"->"未运行";case "missing"->"未安装模块";default->"未确认";},
                "running".equals(status.daemon())?green:red);
        String lock=switch(ClientRules.lockTarget(status.desired(),status.lockSupported())){
            case ClientRules.LOCK_LOCKED->"已设置";case ClientRules.LOCK_NONE->"未设置";
            case ClientRules.LOCK_UNSUPPORTED->"模块过旧，无法确认";default->"未知";
        };
        kv(box,"跨重启锁定",lock,lockTargetColor()==muted?fg:lockTargetColor());
        kv(box,"开机自动应用",bootApplyLabel(status.bootApply()),
                "failed".equals(status.bootApply())||"interrupted".equals(status.bootApply())?red:fg);
        kv(box,"本次接管",status.managed()?"已接管":"未接管",fg);
        String sweep;
        if(!fresh)sweep="读取已过期";
        else if(!"ok".equals(status.lastSweep()))sweep=sweepLabel(status.lastSweep());
        else{
            long age=ClientRules.sweepAgeNow(SystemClock.elapsedRealtime(),reading.elapsed(),status.lastSweepAge());
            sweep=age<0?"时间未知":"通过 · "+age/1000+" 秒前"+(age>ClientRules.SWEEP_FRESH_MS?"（超出窗口）":"");
        }
        kv(box,"巡检",sweep,health().equals(ClientRules.HEALTH_CONFIRMED)?green:amber);
        kv(box,"恢复快照",status.restorable()?"可恢复":"无",fg);
        kv(box,"读取于",formatTime(reading.wallTime())+(fresh?"":" · 已过期"),fresh?muted:amber);
    }
    private void diagnostics() {
        content.setPadding(dp(16),dp(4),dp(16),dp(24));
        showFailure();
        LinearLayout system=group("系统");
        systemRows(system);
        rootProgress();
        SystemIpv6.Status status=systemStatus();
        boolean locked=locked(status);
        boolean lockIntent=status!=null&&status.lockSupported()
                &&!ClientRules.LOCK_NONE.equals(ClientRules.lockTarget(status.desired(),true));
        LinearLayout actions=new LinearLayout(this);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=dp(10);content.addView(actions,ap);
        Button read=button("刷新",R.id.refresh_status,false,()->systemAction("status"));
        read.setEnabled(ClientRules.refreshEnabled(busy()));
        Button pause=button(locked?"仅解除锁定":"停止保持",R.id.pause_system,false,()->systemAction("pause"));
        pause.setEnabled(ClientRules.pauseEnabled(busy(),status!=null&&status.managed(),lockIntent));
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,-2,1);half.setMarginEnd(dp(8));
        actions.addView(read,half);actions.addView(pause,new LinearLayout.LayoutParams(0,-2,1));
        note(locked?"“仅解除锁定”保留当前参数，重启后不再自动接管。":"停止保持后不再纠偏，当前参数不变。",muted);
        if(status!=null&&!status.managed()&&locked)note("锁定意图仍在，但本次开机未接管；服务再次启动时会重试。",amber);

        content.addView(new View(this),new LinearLayout.LayoutParams(-1,dp(16)));
        networkCheck();
        TextView result=text(onlineSummary(),12,muted,false);result.setId(R.id.online_result);
        result.setPadding(0,dp(8),0,0);result.setTextIsSelectable(true);content.addView(result);

        timeline();

        LinearLayout more=group("更多");
        link(more,"导出脱敏诊断",View.NO_ID,()->{
            String report="IPV6 Ctrl "+BuildConfig.VERSION_NAME+"\n"+systemTitle()+"\n"+systemDetail()+"\n"+onlineSummary();
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"分享前请复核诊断内容"));
        });
        link(more,"添加 ipv6 到快捷设置",R.id.add_tile,this::addQuickTile);
        link(more,"开源许可",View.NO_ID,()->{
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(getAssets().open("licenses.txt"),java.nio.charset.StandardCharsets.UTF_8))){
                StringBuilder all=new StringBuilder();String next;while((next=reader.readLine())!=null)all.append(next).append('\n');notice(all.toString());
            }catch(Exception e){notice("许可文件读取失败");}
        });
        note("健康仅在状态读取新鲜、巡检 ok 且在 60 秒窗口内时显示。网络检测只代表当时到 ipify 的路径，受 VPN/DNS 影响；IPv6 不通不能单独证明已阻断。应用不在后台轮询。",muted);
    }
    private void addQuickTile() {
        if(Build.VERSION.SDK_INT<33){notice("下拉快捷设置，点击编辑或 +，找到 ipv6 并添加。亮起表示 IPv6 开启，关闭表示纯 IPv4。");return;}
        try {
            getSystemService(StatusBarManager.class).requestAddTileService(new ComponentName(this,Ipv6TileService.class),"ipv6",
                    Icon.createWithResource(this,R.drawable.ic_ipv6_tile),getMainExecutor(),result->{
                if(isFinishing()||isDestroyed())return;
                notice(result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED||result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                        ?"ipv6 已添加。亮起为 IPv6 开启，关闭为纯 IPv4；长按可打开客户端。"
                        :"未添加。也可下拉快捷设置，点击编辑或 +，手动添加 ipv6。");
            });
        }catch(RuntimeException error){notice("请下拉快捷设置，点击编辑或 +，手动添加 ipv6。");}
    }
    private void notice(String value) { new AlertDialog.Builder(this).setTitle("IPV6 Ctrl").setMessage(value).setPositiveButton("知道了",null).show(); }
    private void work(String action,Runnable job) {
        if(busy())return;operation=action;render();IO.execute(()->{
            try{job.run();}catch(RuntimeException e){failure="操作未完成："+e.getMessage();event("错误","操作未完成",String.valueOf(e.getMessage()));}
            MAIN.post(()->{
                operation="";
                MainActivity shown=current.get();
                if(shown!=null){shown.syncSystemReading();shown.render();}
                maybeAutoCheck();
            });
        });
    }
}
