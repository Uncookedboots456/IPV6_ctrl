"""Native worker + real controller checks on temporary files only (Linux/WSL)."""
import fcntl
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]


def wait_for(condition, timeout=6):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(.05)
    raise AssertionError('condition timed out')


def build_runtime(conf, state, module, boot):
    binary = module / 'ipv6_daemon'
    definitions = dict(PROC_CONF_DIR=conf, STATE_DIR=state, MODULE_DIR=module,
                       BOOT_ID_PATH=boot, SHELL_PATH=shutil.which('mksh') or '/bin/sh')
    subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-DTEST_BUILD',
                    *[f'-D{k}="{v}"' for k, v in definitions.items()],
                    str(ROOT / 'module/daemon.c'), '-o', str(binary)], check=True)
    return binary


class RuntimeTest(unittest.TestCase):
    def test_control_and_reconciliation(self):
        with tempfile.TemporaryDirectory(prefix='ipv6ctrl-runtime-') as directory:
            base = Path(directory)
            conf, state, module, boot = (base / name for name in ['conf', 'state', 'module', 'boot'])
            conf.mkdir(); state.mkdir(); module.mkdir()
            boot.write_text('test-boot\n')
            controller = module / 'control.sh'
            shutil.copyfile(ROOT / 'android/app/src/main/assets/system-ipv6.sh', controller)
            shutil.copyfile(ROOT / 'module/protocol', module / 'protocol')
            binary = build_runtime(conf, state, module, boot)

            def node(name, value):
                path = conf / name / 'disable_ipv6'
                path.parent.mkdir(exist_ok=True)
                path.write_text(str(value) + '\n')
                return path

            def control(action, success=True):
                result = subprocess.run([str(binary), '--control', action], capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
                return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)

            def start():
                process = subprocess.Popen([str(binary)])
                wait_for(lambda: subprocess.run([str(binary), '--check']).returncode == 0)
                return process

            all_node = node('all', 0)
            node('default', 0); node('lo', 0); wifi = node('wlan0', 1)
            # Missing worker must not silently degrade into a one-shot write.
            self.assertIn('error', control('block', False))
            self.assertEqual(all_node.read_text(), '0\n')
            worker = start()
            try:
                wait_for(lambda: (state / 'daemon.status').exists())
                self.assertEqual(control('status')['managed'], 'false')
                self.assertEqual(all_node.read_text(), '0\n')  # startup is idle
                self.assertNotEqual(subprocess.run([str(binary)]).returncode, 0)  # singleton
                self.assertEqual(control('toggle')['managed'], 'true')
                original = (state / 'snapshot').read_bytes()
                control('block')
                self.assertEqual((state / 'snapshot').read_bytes(), original)
                # ON means all zero, even when the original snapshot contained disabled nodes.
                self.assertEqual(control('enable')['state'], 'enabled')
                self.assertFalse((state / 'managed').exists())
                self.assertEqual((state / 'snapshot').read_bytes(), original)
                time.sleep(3.3)
                self.assertEqual(wifi.read_text(), '0\n')
                self.assertEqual(control('toggle')['managed'], 'true')
                wifi.write_text('0\n')  # a mixed state during takeover still toggles ON
                self.assertEqual(control('toggle')['state'], 'enabled')
                self.assertFalse((state / 'managed').exists())
                self.assertEqual((state / 'snapshot').read_bytes(), original)
                self.assertEqual(control('toggle')['state'], 'blocked')
                # Brief sweep/control contention is waited out, not randomly rejected.
                with (state / 'control.lock').open('r+') as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    waiting = subprocess.Popen([str(binary), '--control', 'status'], stdout=subprocess.PIPE)
                    time.sleep(.1); self.assertIsNone(waiting.poll())
                    fcntl.flock(lock, fcntl.LOCK_UN)
                self.assertEqual(waiting.communicate(timeout=3)[0].count(b'state=blocked'), 1)
                self.assertEqual(waiting.returncode, 0)
                with (state / 'control.lock').open('r+') as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    before_toggle=wifi.read_bytes()
                    self.assertEqual(control('toggle', False)['error'], 'operation-locked')
                    self.assertEqual(wifi.read_bytes(), before_toggle)
                all_mtime = all_node.stat().st_mtime_ns
                wifi.write_text('0\n')
                mobile = node('rmnet0', 0)
                wait_for(lambda: wifi.read_text().strip() == mobile.read_text().strip() == '1')
                self.assertEqual(all_node.stat().st_mtime_ns, all_mtime)  # unchanged nodes not rewritten
                self.assertEqual((state / 'snapshot').read_bytes(), original)

                # Never enforce a target whose current-boot snapshot was lost.
                backup = state / 'snapshot.saved'
                with (state / 'control.lock').open('r+') as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    (state / 'snapshot').rename(backup)
                    wifi.write_text('0\n')
                wait_for(lambda: 'snapshot-error' in (state / 'daemon.status').read_text())
                self.assertEqual(wifi.read_text(), '0\n')
                backup.rename(state / 'snapshot')
                wait_for(lambda: wifi.read_text().strip() == '1')

                control('pause')
                wifi.write_text('0\n')
                time.sleep(3.3)
                self.assertEqual(wifi.read_text(), '0\n')
                self.assertEqual(control('restore')['managed'], 'false')
                self.assertEqual(wifi.read_text(), '1\n')
                self.assertEqual(all_node.read_text(), '0\n')
                self.assertEqual(mobile.read_text(), '0\n')
                self.assertFalse((state / 'snapshot').exists())

                # Restore must stop takeover even when a node cannot be restored.
                control('block')
                wifi.unlink(); wifi.mkdir()
                control('enable', False)
                self.assertTrue((state / 'snapshot').exists())
                self.assertFalse((state / 'managed').exists())
                control('restore', False)
                self.assertTrue((state / 'snapshot').exists())
                self.assertFalse((state / 'managed').exists())
                wifi.rmdir(); wifi.write_text('1\n')
                control('restore')

                # Worker death is reported, and restarting resumes only an explicit current-boot target.
                control('block')
                worker.kill(); worker.wait(timeout=3)
                self.assertEqual(control('status')['daemon'], 'stopped')
                control('block', False)
                wifi.write_text('0\n')
                worker = start()
                wait_for(lambda: wifi.read_text().strip() == '1')
                control('restore')
                worker.terminate(); worker.wait(timeout=3)

                # Killed controller automatically releases the kernel operation lock.
                real_controller = controller.read_bytes()
                controller.write_text('read -r ignored\n')
                blocked = subprocess.Popen([str(binary), '--control', 'status'], stdin=subprocess.PIPE)
                with (state / 'control.lock').open('r+') as lock:
                    def locked():
                        try:
                            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                            fcntl.flock(lock, fcntl.LOCK_UN)
                            return False
                        except BlockingIOError:
                            return True
                    wait_for(locked)
                    blocked.kill(); blocked.wait(timeout=3); blocked.stdin.close()
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    fcntl.flock(lock, fcntl.LOCK_UN)
                controller.write_bytes(real_controller)

                # A reboot never adopts all=1 or an old boot's target/snapshot.
                worker = start(); control('block')
                worker.terminate(); worker.wait(timeout=3)
                boot.write_text('next-boot\n'); wifi.write_text('0\n')
                worker = start()
                time.sleep(3.3)
                self.assertEqual(wifi.read_text(), '0\n')
                status = control('status')
                self.assertEqual(status['managed'], 'false')
                self.assertEqual(status['restorable'], 'false')

                # Disabling stops the worker; restoration remains available while disabled.
                control('block')
                (module / 'disable').touch()
                worker.wait(timeout=6)
                self.assertEqual(control('status')['managed'], 'false')
                control('block', False)
                control('restore')
                self.assertEqual(wifi.read_text(), '0\n')
            finally:
                if worker.poll() is None:
                    worker.terminate(); worker.wait(timeout=4)

    def test_persistent_intent_and_boot_adoption(self):
        with tempfile.TemporaryDirectory(prefix='ipv6ctrl-intent-') as directory:
            base = Path(directory)
            conf, state, module, boot = (base / name for name in ['conf', 'state', 'module', 'boot'])
            conf.mkdir(); state.mkdir(); module.mkdir()
            boot.write_text('boot-one\n')
            controller = module / 'control.sh'
            shutil.copyfile(ROOT / 'android/app/src/main/assets/system-ipv6.sh', controller)
            shutil.copyfile(ROOT / 'module/protocol', module / 'protocol')
            binary = build_runtime(conf, state, module, boot)

            def node(name, value):
                path = conf / name / 'disable_ipv6'
                path.parent.mkdir(exist_ok=True)
                path.write_text(str(value) + '\n')
                return path

            def control(action, success=True):
                result = subprocess.run([str(binary), '--control', action], capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
                return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)

            workers = []

            def start():
                process = subprocess.Popen([str(binary)])
                workers.append(process)
                wait_for(lambda: subprocess.run([str(binary), '--check']).returncode == 0)
                return process

            all_node = node('all', 0); node('default', 0); node('lo', 0); wifi = node('wlan0', 1)
            try:
                # Adoption neither requires a worker nor acts without an intent.
                self.assertNotEqual(subprocess.run([str(binary), '--check']).returncode, 0)
                status = control('adopt')
                self.assertEqual(status['state'], 'mixed')
                self.assertEqual(status['desired'], 'none')
                self.assertEqual(status['boot_apply'], 'none')
                self.assertFalse((state / 'snapshot').exists())
                self.assertFalse((state / 'managed').exists())
                self.assertEqual(wifi.read_text(), '1\n')
                failure = control('lock', False)
                self.assertEqual(failure['error'], 'runtime-daemon-not-running')

                worker = start()
                status = control('lock')
                self.assertEqual(status['state'], 'blocked')
                self.assertEqual(status['managed'], 'true')
                self.assertEqual(status['desired'], 'locked')
                self.assertEqual(status['boot_apply'], 'none')
                self.assertEqual((state / 'desired').read_text(), 'ipv4-lock\n')
                self.assertEqual((state / 'snapshot').read_text().splitlines()[0], 'boot-one')
                wait_for(lambda: control('status')['last_sweep'] == 'ok', timeout=7)
                status = control('status')
                self.assertTrue(status['last_sweep_boottime'].isdigit(), status)
                self.assertTrue(status['last_sweep_age'].isdigit(), status)

                # A failed persistent write is reported as current-boot only.
                (state / 'desired').unlink(); (state / 'desired').mkdir()
                failure = control('lock', False)
                self.assertEqual(failure['error'], 'lock-persist-failed-current-only')
                self.assertEqual(failure['managed'], 'true')
                self.assertTrue((state / 'desired').is_dir())
                (state / 'desired').rmdir()
                control('pause')
                self.assertFalse((state / 'managed').exists())
                self.assertTrue((state / 'snapshot').exists())
                control('lock')

                # A new boot captures a new baseline and reuses it on repeated adoption.
                worker.terminate(); worker.wait(timeout=3)
                boot.write_text('boot-two\n')
                node('all', 0); node('default', 0); node('lo', 0); wifi.write_text('0\n')
                # Adoption must not need the worker singleton lock.
                with (state / 'daemon.lock').open('a+') as singleton:
                    fcntl.flock(singleton, fcntl.LOCK_EX)
                    status = control('adopt')
                self.assertEqual(status['state'], 'blocked')
                self.assertEqual(status['managed'], 'true')
                self.assertEqual(status['desired'], 'locked')
                self.assertEqual(status['boot_apply'], 'applied')
                self.assertEqual(wifi.read_text(), '1\n')
                snapshot = (state / 'snapshot').read_text()
                self.assertEqual(snapshot.splitlines()[0], 'boot-two')
                self.assertIn('wlan0 0', snapshot)
                saved = (state / 'snapshot').read_bytes()
                status = control('adopt')
                self.assertEqual(status['boot_apply'], 'applied')
                self.assertEqual((state / 'snapshot').read_bytes(), saved)

                # Pause unlocks; the following boot stays passive.
                worker = start()
                wait_for(lambda: control('status')['last_sweep'] == 'ok', timeout=7)
                control('pause')
                self.assertEqual(control('status')['desired'], 'none')
                self.assertFalse((state / 'managed').exists())
                worker.terminate(); worker.wait(timeout=3)
                boot.write_text('boot-three\n')
                node('all', 0); node('default', 0); node('lo', 0); wifi.write_text('0\n')
                status = control('adopt')
                self.assertEqual(status['desired'], 'none')
                self.assertEqual(status['managed'], 'false')
                self.assertEqual(status['boot_apply'], 'none')
                self.assertEqual(wifi.read_text(), '0\n')
                self.assertFalse((state / 'managed').exists())

                # An interrupted marker is one attempt for the boot and worker keeps the error.
                (state / 'desired').write_text('ipv4-lock\n')
                (state / 'boot_apply').write_text('boot-three\ninterrupted\napplying\n')
                failure = control('adopt', False)
                self.assertEqual(failure['error'], 'boot-apply-already-attempted')
                self.assertFalse((state / 'managed').exists())
                self.assertEqual(wifi.read_text(), '0\n')
                worker = start()
                wait_for(lambda: control('status')['last_sweep'] == 'boot-apply-interrupted', timeout=7)
                status = control('status')
                self.assertEqual(status['boot_apply'], 'interrupted')
                self.assertTrue(status['last_sweep_boottime'].isdigit(), status)
                # A verified explicit lock retires the interrupted marker without faking adopt.
                status = control('lock')
                self.assertEqual(status['state'], 'blocked')
                self.assertEqual(status['managed'], 'true')
                self.assertEqual(status['desired'], 'locked')
                self.assertEqual(status['boot_apply'], 'manual')
                snapshot = (state / 'snapshot').read_bytes()
                status = control('lock')
                self.assertEqual(status['boot_apply'], 'manual')
                self.assertEqual((state / 'snapshot').read_bytes(), snapshot)
                status = control('adopt')
                self.assertEqual(status['boot_apply'], 'manual')
                self.assertEqual((state / 'snapshot').read_bytes(), snapshot)
                wait_for(lambda: control('status')['last_sweep'] == 'ok', timeout=7)
                status = control('status')
                self.assertEqual(status['boot_apply'], 'manual')
                worker.terminate(); worker.wait(timeout=3)
                control('restore')

                # Snapshot capture failure is fixed, preserves intent and writes no node.
                boot.write_text('boot-four\n')
                (state / 'boot_apply').unlink()
                (state / 'desired').write_text('ipv4-lock\n')
                all_node.unlink()
                before = {name: (conf / name / 'disable_ipv6').read_text()
                          for name in ['default', 'lo', 'wlan0']}
                failure = control('adopt', False)
                self.assertEqual(failure['error'], 'snapshot-failed')
                self.assertEqual((state / 'boot_apply').read_text().splitlines()[1], 'failed')
                self.assertEqual((state / 'boot_apply').read_text().splitlines()[2], 'snapshot-failed')
                self.assertTrue((state / 'desired').exists())
                self.assertFalse((state / 'managed').exists())
                self.assertFalse((state / 'snapshot').exists())
                for name, value in before.items():
                    self.assertEqual((conf / name / 'disable_ipv6').read_text(), value)
                # The worker keeps reporting the failed attempt instead of idle and never retries.
                node('all', 0)
                worker = start()
                wait_for(lambda: control('status')['last_sweep'] == 'boot-apply-failed', timeout=7)
                self.assertEqual(control('status')['boot_apply'], 'failed')
                self.assertEqual(wifi.read_text(), '0\n')
                self.assertFalse((state / 'managed').exists())

                # Failing to retire the marker keeps the old error, the lock and its snapshot.
                (state / 'boot_apply').write_text('boot-four\nfailed\napply-failed\n')
                (state / 'boot_apply.pending').mkdir()
                failure = control('lock', False)
                self.assertEqual(failure['error'], 'lock-resolve-failed')
                self.assertTrue((state / 'managed').exists())
                self.assertEqual((state / 'desired').read_text(), 'ipv4-lock\n')
                self.assertEqual((state / 'boot_apply').read_text().splitlines()[1], 'failed')
                saved = (state / 'snapshot').read_bytes()
                (state / 'boot_apply.pending').rmdir()

                # A failed persistent write also cannot retire the old failure marker.
                (state / 'boot_apply').write_text('boot-four\nfailed\napply-failed\n')
                (state / 'desired').unlink(); (state / 'desired').mkdir()
                failure = control('lock', False)
                self.assertEqual(failure['error'], 'lock-persist-failed-current-only')
                self.assertEqual((state / 'boot_apply').read_text().splitlines()[1], 'failed')
                self.assertEqual((state / 'snapshot').read_bytes(), saved)
                (state / 'desired').rmdir()

                # A later verified lock resolves the old failure and is idempotent.
                status = control('lock')
                self.assertEqual(status['boot_apply'], 'manual')
                status = control('lock')
                self.assertEqual(status['boot_apply'], 'manual')
                self.assertEqual((state / 'snapshot').read_bytes(), saved)
                status = control('adopt')
                self.assertEqual(status['boot_apply'], 'manual')
                self.assertEqual((state / 'snapshot').read_bytes(), saved)
                wait_for(lambda: control('status')['last_sweep'] == 'ok', timeout=7)
                self.assertEqual(control('status')['boot_apply'], 'manual')
                worker.terminate(); worker.wait(timeout=3)
                control('restore')
            finally:
                for process in workers:
                    if process.poll() is None:
                        process.terminate(); process.wait(timeout=4)

    def test_service_supervises_worker_without_repeated_adopt(self):
        with tempfile.TemporaryDirectory(prefix='ipv6ctrl-service-') as directory:
            base = Path(directory)
            module = base / 'module'
            module.mkdir()
            shutil.copyfile(ROOT / 'module/service.sh', module / 'service.sh')
            log = base / 'calls'
            fake = module / 'ipv6_daemon'
            fake.write_text('#!/bin/sh\nprintf \'%s\\n\' "$*" >> "$LOG"\nexit 0\n')
            fake.chmod(0o755)
            bindir = base / 'bin'
            bindir.mkdir()
            sleep = bindir / 'sleep'
            sleep.write_text('#!/bin/sh\nexit 0\n')
            sleep.chmod(0o755)
            environment = dict(os.environ, PATH=str(bindir) + os.pathsep + os.environ['PATH'], LOG=str(log))

            def supervise():
                subprocess.run([shutil.which('sh'), str(module / 'service.sh')],
                               env=environment, check=True, timeout=10)

            supervise()
            calls = log.read_text().splitlines()
            self.assertEqual(calls.count('--control adopt'), 1)
            self.assertEqual(calls.count(''), 3)  # initial worker plus two bounded restarts
            log.unlink()

            (module / 'disable').touch()
            supervise()
            self.assertFalse(log.exists())
            (module / 'disable').unlink()
            (module / 'remove').touch()
            supervise()
            self.assertFalse(log.exists())


if __name__ == '__main__':
    unittest.main()
