"""Build/run an Android Runtime fixture; refuses production paths and compares real sysctls."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--ndk-home', default=os.environ.get('ANDROID_NDK_HOME', '/opt/android-ndk-r29'))
    args = parser.parse_args()
    name = 'ipv6ctrl-runtime-' + uuid.uuid4().hex[:12]
    remote = '/data/local/tmp/' + name
    local = ROOT / 'android/build/device-debug' / name
    local.mkdir(parents=True)

    def adb(*argv, timeout=90):
        return subprocess.run(['adb', '-s', args.serial, *argv], capture_output=True, text=True,
                              encoding='utf-8', errors='replace', timeout=timeout, check=True).stdout

    def shell(command):
        return adb('shell', 'su -c ' + shlex.quote(command))

    def parameters():
        return shell('for f in /proc/sys/net/ipv6/conf/*/disable_ipv6; do printf "%s " "$f"; cat "$f"; done')

    before = parameters()
    (local / 'parameters-before-private.txt').write_text(before, encoding='utf-8')
    binary = local / 'ipv6_daemon'
    definitions = dict(PROC_CONF_DIR=remote+'/conf', STATE_DIR=remote+'/state',
                       MODULE_DIR=remote+'/module', BOOT_ID_PATH=remote+'/boot')
    flags = ['-DTEST_BUILD', *[f'-D{k}="{v}"' for k, v in definitions.items()]]
    if os.name == 'nt':
        def linux_path(path):
            return subprocess.check_output(['wsl', '--', 'wslpath', '-a', path.as_posix()], text=True).strip()
        build = local / 'build.sh'
        build.write_text('cd '+shlex.quote(linux_path(ROOT))+'\n'+shlex.join([
            'env', 'ANDROID_NDK_HOME='+args.ndk_home, 'sh', 'scripts/build_runtime.sh',
            linux_path(binary), *flags])+'\n', encoding='utf-8', newline='\n')
        subprocess.run(['wsl', '--', 'sh', linux_path(build)], check=True)
    else:
        subprocess.run(['sh', str(ROOT/'scripts/build_runtime.sh'), str(binary), *flags],
                       env={**os.environ, 'ANDROID_NDK_HOME': args.ndk_home}, check=True)

    shell('[ ! -e '+shlex.quote(remote)+' ] && mkdir -p '+shlex.quote(remote+'/module'))
    for source, destination in [(binary, 'module/ipv6_daemon'),
                                (ROOT/'module/protocol', 'module/protocol'),
                                (ROOT/'android/app/src/main/assets/system-ipv6.sh', 'module/control.sh'),
                                (ROOT/'tests/android_runtime_smoke.sh', 'smoke.sh')]:
        adb('push', str(source), remote+'/'+destination)
    shell('chmod 700 '+shlex.quote(remote+'/module/ipv6_daemon'))
    try:
        output = shell('sh '+shlex.quote(remote+'/smoke.sh')+' '+shlex.quote(remote))
        (local/'result.txt').write_text(output, encoding='utf-8')
        print(output, end='')
    except subprocess.CalledProcessError as error:
        (local/'failure-private.txt').write_text(error.stdout+'\n'+error.stderr, encoding='utf-8')
        raise
    finally:
        # A timed-out adb client must not leave its isolated worker running.
        shell('pid=$(cat '+shlex.quote(remote+'/worker.pid')+' 2>/dev/null); '
              '[ -n "$pid" ] && [ "$(readlink /proc/$pid/exe)" = '+shlex.quote(remote+'/module/ipv6_daemon')+
              ' ] && kill "$pid"; true')
        after = parameters()
        (local/'parameters-after-private.txt').write_text(after, encoding='utf-8')
        if before != after:
            raise AssertionError('real IPv6 parameters changed; inspect private before/after records')
    print('PASS real kernel parameters unchanged')
    # Exact unique directory created by this run; the binary must no longer hold its lock.
    shell('! '+shlex.quote(remote+'/module/ipv6_daemon')+' --check && rm -r '+shlex.quote(remote))


if __name__ == '__main__':
    main()
