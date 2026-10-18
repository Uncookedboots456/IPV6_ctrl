"""Native worker + real controller checks on temporary files only (Linux/WSL)."""
import fcntl
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
            binary = module / 'ipv6_daemon'
            definitions = dict(PROC_CONF_DIR=conf, STATE_DIR=state, MODULE_DIR=module,
                               BOOT_ID_PATH=boot, SHELL_PATH=shutil.which('mksh') or '/bin/sh')
            subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-DTEST_BUILD',
                            *[f'-D{k}="{v}"' for k, v in definitions.items()],
                            str(ROOT / 'module/daemon.c'), '-o', str(binary)], check=True)

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


if __name__ == '__main__':
    unittest.main()
