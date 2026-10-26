"""Exercise the real Root I/O code with a local JVM child, never su or Android."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path(os.environ.get('JAVA_HOME', ''))
JAVAC = shutil.which('javac') or next(iter(JAVA_HOME.glob('bin/javac*')), None)
JAVA = shutil.which('java') or next(iter(JAVA_HOME.glob('bin/java.exe')), None)


@unittest.skipUnless(JAVAC and JAVA, 'JDK required for isolated Root I/O check')
class RootIoTest(unittest.TestCase):
    def test_blocked_input_deadline_and_real_output(self):
        source = (ROOT / 'android/app/src/main/java/dev/ipv6ctrl/SystemIpv6.java').read_text(encoding='utf-8')
        command = 'new ProcessBuilder("su", "-c", "sh -s -- " + action)'
        self.assertEqual(source.count(command), 1)
        # Replace only the external command and duration; retain production I/O and parsing.
        source = source.replace(command, '''new ProcessBuilder(System.getProperty("java.home")+"/bin/java",
            "-cp",System.getProperty("java.class.path"),"dev.ipv6ctrl.RootIoCheck",
            "child",System.getProperty("root.test.mode","ok"))''')
        self.assertEqual(source.count('waitFor(30, TimeUnit.SECONDS)'), 1)
        source = source.replace('waitFor(30, TimeUnit.SECONDS)', 'waitFor(1, TimeUnit.SECONDS)')
        files = {
            'dev/ipv6ctrl/SystemIpv6.java': source,
            'dev/ipv6ctrl/ClientRules.java': (ROOT / 'android/app/src/main/java/dev/ipv6ctrl/ClientRules.java').read_text(encoding='utf-8'),
            'dev/ipv6ctrl/BuildConfig.java': 'package dev.ipv6ctrl; final class BuildConfig { static final boolean DEBUG=false; }',
            'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }',
            'android/util/Log.java': '''package android.util; public class Log {
                public static int d(String a,String b){return 0;}
                public static int w(String a,String b,Throwable c){return 0;} }''',
            'android/content/Context.java': '''package android.content;
                public class Context {
                    public Assets getAssets(){return new Assets();}
                    public Preferences getSharedPreferences(String n,int m){return new Preferences();}
                    public static class Assets {
                        public java.io.InputStream open(String n){return new java.io.ByteArrayInputStream(new byte[1048576]);}
                    }
                    public static class Preferences {
                        public Preferences edit(){return this;}
                        public Preferences putLong(String n,long v){return this;}
                        public Preferences putBoolean(String n,boolean v){return this;}
                        public Preferences remove(String n){return this;}
                        public void apply(){}
                    }
                }''',
            'dev/ipv6ctrl/RootIoCheck.java': '''package dev.ipv6ctrl;
                public class RootIoCheck {
                    private static void output(String mode){
                        if(mode.equals("new")){System.out.println("state=blocked\\ndisabled=3\\ntotal=3\\nrestorable=true\\ndaemon=running\\nmanaged=true\\nlast_sweep=ok\\nlast_sweep_boottime=120\\nlast_sweep_age=4\\ndesired=locked\\nboot_apply=applied");return;}
                        if(mode.equals("manual")){System.out.println("state=blocked\\ndisabled=3\\ntotal=3\\nrestorable=true\\ndaemon=running\\nmanaged=true\\nlast_sweep=ok\\nlast_sweep_boottime=120\\nlast_sweep_age=4\\ndesired=locked\\nboot_apply=manual");return;}
                        if(mode.equals("old")){System.out.println("state=enabled\\ndisabled=0\\ntotal=2\\nrestorable=false\\ndaemon=running\\nmanaged=false\\nlast_sweep=idle");return;}
                        if(mode.equals("invalid")){System.out.println("state=blocked\\ndisabled=2\\ntotal=3\\nrestorable=true\\ndaemon=running\\nmanaged=true\\nlast_sweep=ok\\nlast_sweep_boottime=abc\\nlast_sweep_age=-2\\ndesired=weird\\nboot_apply=weird");return;}
                        if(mode.equals("badcount")){System.out.println("state=blocked\\ndisabled=4\\ntotal=3\\nrestorable=true\\ndaemon=running\\nmanaged=true\\nlast_sweep=ok");return;}
                        System.out.println("state=enabled\\ndisabled=0\\ntotal=2\\nrestorable=false\\ndaemon=running\\nmanaged=false\\nlast_sweep=idle");
                    }
                    public static void main(String[] args) throws Exception {
                        if(args.length>0){
                            String mode=args[1];
                            if(mode.equals("blocked")){Thread.sleep(10000);return;}
                            if(mode.equals("exit2")){System.exit(2);}
                            if(mode.equals("error")){System.out.println("error=runtime-update-required");System.exit(1);}
                            output(mode);return;
                        }
                        var context=new android.content.Context();
                        assert SystemIpv6.run(context,"status").state().equals("enabled");
                        System.setProperty("root.test.mode","new");
                        var current=SystemIpv6.run(context,"status");
                        assert current.desired().equals("locked");
                        assert current.bootApply().equals("applied");
                        assert current.lastSweepBoottime()==120 && current.lastSweepAge()==4;
                        assert current.lockSupported();
                        assert ClientRules.HEALTH_CONFIRMED.equals(ClientRules.health(current.state(),current.daemon(),current.managed(),current.desired(),current.bootApply(),current.lastSweep(),true,ClientRules.sweepAgeNow(2000,1000,4)));
                        System.setProperty("root.test.mode","manual");
                        var manual=SystemIpv6.run(context,"status");
                        assert manual.bootApply().equals("manual");
                        assert ClientRules.HEALTH_CONFIRMED.equals(ClientRules.health(manual.state(),manual.daemon(),manual.managed(),manual.desired(),manual.bootApply(),manual.lastSweep(),true,ClientRules.sweepAgeNow(2000,1000,4)));
                        System.setProperty("root.test.mode","old");
                        var old=SystemIpv6.run(context,"status");
                        assert old.desired().equals("unknown") && old.bootApply().equals("unknown");
                        assert old.lastSweepBoottime()==SystemIpv6.UNKNOWN_MILLIS && old.lastSweepAge()==SystemIpv6.UNKNOWN_MILLIS;
                        assert !old.lockSupported() : "missing fields must not look lock-capable";
                        assert !ClientRules.HEALTH_CONFIRMED.equals(ClientRules.health(old.state(),old.daemon(),old.managed(),old.desired(),old.bootApply(),old.lastSweep(),true,-1));
                        System.setProperty("root.test.mode","invalid");
                        var invalid=SystemIpv6.run(context,"status");
                        assert invalid.desired().equals("unknown") && invalid.bootApply().equals("unknown");
                        assert invalid.lastSweepBoottime()==SystemIpv6.UNKNOWN_MILLIS && invalid.lastSweepAge()==SystemIpv6.UNKNOWN_MILLIS;
                        assert invalid.lockSupported();
                        System.setProperty("root.test.mode","badcount");
                        try{SystemIpv6.run(context,"status");throw new AssertionError("invalid count accepted");}
                        catch(IllegalStateException expected){assert expected.getMessage().contains("计数无效");}
                        System.setProperty("root.test.mode","ok");
                        SystemIpv6.run(context,"status");
                        long changes=SystemIpv6.changeCount();
                        SystemIpv6.run(context,"status");
                        assert SystemIpv6.changeCount()==changes : "status must not invalidate online results";
                        SystemIpv6.run(context,"lock");
                        assert SystemIpv6.changeCount()==changes+1 : "lock must invalidate old online results";
                        try{SystemIpv6.run(context,"adopt");throw new AssertionError("private adopt action accepted");}
                        catch(IllegalArgumentException expected){}
                        System.setProperty("root.test.mode","exit2");
                        try{SystemIpv6.run(context,"lock");throw new AssertionError("old Runtime lock looked successful");}
                        catch(IllegalStateException expected){assert expected.getMessage().contains("不支持跨重启锁定");}
                        System.setProperty("root.test.mode","error");
                        try{SystemIpv6.run(context,"toggle");throw new AssertionError("false success");}
                        catch(IllegalStateException expected){assert expected.getMessage().contains("更新配套");}
                        System.setProperty("root.test.mode","blocked");
                        long started=System.nanoTime();
                        try{SystemIpv6.run(context,"toggle");throw new AssertionError("unbounded input write");}
                        catch(IllegalStateException expected){assert expected.getMessage().contains("超时");}
                        assert System.nanoTime()-started<5000000000L : "timeout cleanup hung";
                        assert SystemIpv6.latest.status()==null;
                        try{SystemIpv6.run(context,"status; invalid");throw new AssertionError("invalid action accepted");}
                        catch(IllegalArgumentException expected){}
                    }
                }''',
        }
        with tempfile.TemporaryDirectory(prefix='ipv6ctrl-root-io-') as directory:
            base = Path(directory)
            for name, content in files.items():
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf-8')
            subprocess.run([str(JAVAC), '-encoding', 'UTF-8', '-d', str(base),
                            *[str(base / name) for name in files]], check=True, capture_output=True, timeout=30)
            result = subprocess.run([str(JAVA), '-ea', '-cp', str(base), 'dev.ipv6ctrl.RootIoCheck'],
                                    capture_output=True, text=True, encoding='utf-8', timeout=15)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
