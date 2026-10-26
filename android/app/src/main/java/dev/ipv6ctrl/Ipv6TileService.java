package dev.ipv6ctrl;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** SystemUI owns this bound service; the Root worker owns continued IPv4 fallback. */
public final class Ipv6TileService extends TileService {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean switching=new AtomicBoolean();
    private static volatile SystemIpv6.Status confirmed;
    private static volatile long switchStarted;
    private static String switchingText="正在切换…";
    private static WeakReference<Ipv6TileService> visible=new WeakReference<>(null);
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean listening,reading;
    private void trace(String event) { if(BuildConfig.DEBUG)android.util.Log.d("IPV6CtrlTile",System.identityHashCode(this)+" "+event+" listening="+listening+" switching="+switching.get()); }

    @Override public void onStartListening() {
        super.onStartListening();trace("start-listening");listening=true;visible=new WeakReference<>(this);refresh();
    }
    @Override public void onStopListening() { detach();super.onStopListening(); }
    @Override public void onDestroy() { detach();super.onDestroy(); }
    private void detach() { trace("detach");listening=false;if(visible.get()==this)visible.clear(); }
    // Non-active tile: read each time the panel opens, with no background poll or API request.
    static void refreshVisible() {
        Ipv6TileService service=visible.get();if(service!=null)service.main.post(service::refresh);
    }
    private void refresh() {
        if(!listening)return;
        if(switching.get()){showBusy();return;}
        if(!getSharedPreferences("ui",0).getBoolean("root_ready",false)){
            show(Tile.STATE_INACTIVE,"点按打开应用授权 Root");return;
        }
        if(reading)return;reading=true;showConfirmed("正在读取…");
        IO.execute(()->{
            try{SystemIpv6.run(getApplicationContext(),"status");}catch(RuntimeException ignored){}
            main.post(()->{reading=false;if(switching.get())showBusy();else present();});
        });
    }
    @Override public void onClick() {
        super.onClick();trace("click");
        if(isLocked())unlockAndRun(this::toggle);else toggle();
    }
    private void toggle() {
        if(!getSharedPreferences("ui",0).getBoolean("root_ready",false)){openApp();return;}
        if(reading||switching.get())return;
        SystemIpv6.Status before=SystemIpv6.latest.status();
        if(before==null){refresh();return;}
        if(!switching.compareAndSet(false,true))return;
        confirmed=before;switchStarted=SystemClock.elapsedRealtime();
        switchingText=isOn(confirmed)?"正在关闭…":"正在开启…";showBusy();
        main.postDelayed(()->{Ipv6TileService service=visible.get();if(switching.get()&&service!=null)service.showBusy();},10000);
        var context=getApplicationContext();
        IO.execute(()->{
            trace("toggle-start");
            try{SystemIpv6.run(context,"toggle");}
            catch(RuntimeException failure){
                context.getSharedPreferences("ui",0).edit().putString("tile_error",failure.getMessage()==null?"无法完成快捷切换":failure.getMessage()).apply();
                // Read back after a rejected/failed write; never infer success from the click.
                try{SystemIpv6.run(context,"status");}catch(RuntimeException ignored){}
            }finally{
                trace("toggle-finished elapsed="+(SystemClock.elapsedRealtime()-switchStarted));
                SystemIpv6.Status observed=SystemIpv6.latest.status();if(observed!=null)confirmed=observed;
                switching.set(false);
                main.post(()->{Ipv6TileService service=visible.get();if(service!=null)service.present();});
            }
        });
    }
    private static boolean isOn(SystemIpv6.Status status){return !status.managed()&&!status.state().equals("blocked");}
    private void showConfirmed(String detail){show(confirmed==null?Tile.STATE_UNAVAILABLE:isOn(confirmed)?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE,detail);}
    private void showBusy(){showConfirmed(SystemClock.elapsedRealtime()-switchStarted>=10000?"切换较慢 · 等待核对":switchingText);}
    private void present() {
        SystemIpv6.Status status=SystemIpv6.latest.status();
        if(status==null){showConfirmed("状态未确认 · 长按检查");return;}
        confirmed=status;
        if(getSharedPreferences("ui",0).contains("tile_error")){showConfirmed("切换失败 · 长按查看");return;}
        boolean lockIntent=status.lockSupported()&&!ClientRules.LOCK_NONE.equals(ClientRules.lockTarget(status.desired(),true));
        if(lockIntent&&("failed".equals(status.bootApply())||"interrupted".equals(status.bootApply()))){
            show(Tile.STATE_INACTIVE,"锁定未生效 · 长按检查");return;
        }
        if(status.managed()&&!"running".equals(status.daemon())){show(Tile.STATE_INACTIVE,"保持中断 · 长按检查");return;}
        if(status.managed()){
            show(Tile.STATE_INACTIVE,status.state().equals("blocked")?"已关闭 · 纯 IPv4":"关闭保持中 · 部分接口启用");
        }else if(status.state().equals("enabled"))show(Tile.STATE_ACTIVE,"已开启");
        else if(status.state().equals("blocked"))show(Tile.STATE_INACTIVE,"已关闭 · 未持续保持");
        else show(Tile.STATE_ACTIVE,"已开启 · 部分接口停用");
    }
    private void show(int state,String detail) {
        trace("show "+state+" "+detail);if(!listening)return;
        Tile tile=getQsTile();if(tile==null)return;
        tile.setLabel("ipv6");tile.setState(state);tile.setContentDescription("ipv6，"+detail);
        if(Build.VERSION.SDK_INT>=29)tile.setSubtitle(detail);
        if(Build.VERSION.SDK_INT>=30)tile.setStateDescription(detail);
        tile.updateTile();
    }
    // Android 9–13 have no PendingIntent overload; Android 14+ always takes the PendingIntent branch.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent intent=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        else startActivityAndCollapse(intent);
    }
}
