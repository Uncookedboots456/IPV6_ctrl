"""Exercise the Android script against temporary files, never real sysctls."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "ipv6_trial.sh"


class TrialTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ipv6-trial-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.conf = self.root / "conf"
        self.net = self.root / "net"
        self.state = self.root / "state"
        self.boot = self.root / "boot_id"
        self.boot.write_text("test-boot\n")
        for index, name in enumerate(("all", "default", "lo", "wlan0", "rmnet0"), 1):
            (self.conf / name).mkdir(parents=True)
            self.node(name).write_text("0\n")
            (self.net / name).mkdir(parents=True)
            (self.net / name / "ifindex").write_text(f"{index}\n")
        source = SCRIPT.read_text()
        for old, new in (
            ("PROC_CONF=/proc/sys/net/ipv6/conf", f"PROC_CONF='{self.conf}'"),
            ("NET_CLASS=/sys/class/net", f"NET_CLASS='{self.net}'"),
            ("BOOT_ID_FILE=/proc/sys/kernel/random/boot_id", f"BOOT_ID_FILE='{self.boot}'"),
            ("STATE_DIR=/data/adb/ipv6_ctrl_trial", f"STATE_DIR='{self.state}'"),
        ):
            assert old in source
            source = source.replace(old, new)
        self.script = self.root / "trial.sh"
        self.script.write_text(source)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.stub("id", 'printf "%s\\n" "${TRIAL_TEST_UID:-0}"\n')
        self.stub("pidof", '[ "${TRIAL_TEST_DAEMON:-0}" = 1 ]\n')
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}")

    def node(self, name):
        return self.conf / name / "disable_ipv6"

    def stub(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)

    def run_trial(self, *args, **env):
        return subprocess.run(
            ["sh", str(self.script), *args],
            env=dict(self.env, **env), text=True, capture_output=True, timeout=3,
        )

    def values(self):
        return {node.parent.name: node.read_text() for node in self.conf.glob("*/disable_ipv6")}

    def test_status_has_no_side_effects(self):
        before = self.values()
        result = self.run_trial("status")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("wlan0", result.stdout)
        self.assertEqual(self.values(), before)
        self.assertFalse(self.state.exists())

    def test_only_selected_interface_changes_and_restore_is_exact(self):
        before = self.values()
        result = self.run_trial("disable", "wlan0")
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = dict(before, wlan0="1\n")
        self.assertEqual(self.values(), expected)
        self.assertEqual((self.state / "snapshot").read_text(), "test-boot wlan0 4 0\n")
        self.assertEqual((self.state / "snapshot").stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o700)
        result = self.run_trial("restore")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.values(), before)
        self.assertFalse((self.state / "snapshot").exists())
        self.assertFalse((self.state / "lock").exists())

    def test_second_trial_preserves_original_snapshot(self):
        self.assertEqual(self.run_trial("disable", "wlan0").returncode, 0)
        saved = (self.state / "snapshot").read_bytes()
        result = self.run_trial("disable", "rmnet0")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.state / "snapshot").read_bytes(), saved)
        self.assertEqual(self.node("rmnet0").read_text(), "0\n")

    def test_already_disabled_is_not_rewritten(self):
        self.node("wlan0").write_text("1\n")
        before = self.node("wlan0").stat().st_mtime_ns
        self.assertEqual(self.run_trial("disable", "wlan0").returncode, 0)
        self.assertEqual(self.node("wlan0").stat().st_mtime_ns, before)
        self.assertFalse((self.state / "snapshot").exists())

    def test_global_loopback_and_path_inputs_are_rejected(self):
        before = self.values()
        for name in ("all", "default", "lo", ".", "..", "../wlan0", "wlan*", ""):
            with self.subTest(name=name):
                self.assertNotEqual(self.run_trial("disable", name).returncode, 0)
        self.assertEqual(self.values(), before)
        self.assertFalse((self.state / "snapshot").exists())

    def test_running_daemon_and_non_root_are_rejected(self):
        before = self.values()
        for env in ({"TRIAL_TEST_DAEMON": "1"}, {"TRIAL_TEST_UID": "2000"}):
            with self.subTest(env=env):
                self.assertNotEqual(self.run_trial("disable", "wlan0", **env).returncode, 0)
        self.assertEqual(self.values(), before)
        self.assertFalse(self.state.exists())

    def test_competing_writer_causes_failure_and_restores_original(self):
        self.stub("cat", '''if [ "$1" = "${TRIAL_TEST_DRIFT_NODE:-}" ] && [ "$(/bin/cat "$1")" = 1 ]; then
    printf '0\\n' > "$1"
fi
exec /bin/cat "$@"
''')
        before = self.values()
        result = self.run_trial("disable", "wlan0", TRIAL_TEST_DRIFT_NODE=str(self.node("wlan0")))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("attempting to restore", result.stderr)
        self.assertEqual(self.values(), before)
        self.assertFalse((self.state / "snapshot").exists())

    def test_failed_write_returns_failure_and_clears_restored_snapshot(self):
        self.node("wlan0").unlink()
        self.node("wlan0").symlink_to("/dev/full")
        self.stub("cat", '''if [ "$1" = "${TRIAL_TEST_UNWRITABLE_NODE:-}" ]; then
    printf '0\\n'
else
    exec /bin/cat "$@"
fi
''')
        result = self.run_trial("disable", "wlan0", TRIAL_TEST_UNWRITABLE_NODE=str(self.node("wlan0")))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("attempting to restore", result.stderr)
        self.assertNotIn("Applied once", result.stdout)
        self.assertFalse((self.state / "snapshot").exists())
        for name in ("all", "default", "lo", "rmnet0"):
            self.assertEqual(self.node(name).read_text(), "0\n")

    def test_restore_refuses_previous_boot_or_recreated_interface(self):
        self.assertEqual(self.run_trial("disable", "wlan0").returncode, 0)
        self.boot.write_text("next-boot\n")
        result = self.run_trial("restore")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("previous boot", result.stderr)
        self.boot.write_text("test-boot\n")
        (self.net / "wlan0" / "ifindex").write_text("99\n")
        result = self.run_trial("restore")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("recreated", result.stderr)
        self.assertEqual(self.node("wlan0").read_text(), "1\n")
        self.assertTrue((self.state / "snapshot").exists())

    def test_unavailable_interface_retains_recovery_snapshot(self):
        self.assertEqual(self.run_trial("disable", "wlan0").returncode, 0)
        self.node("wlan0").unlink()
        result = self.run_trial("restore")
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.state / "snapshot").exists())
        self.assertFalse((self.state / "lock").exists())

    def test_existing_lock_prevents_changes(self):
        self.state.mkdir()
        (self.state / "lock").mkdir()
        before = self.values()
        self.assertNotEqual(self.run_trial("disable", "wlan0").returncode, 0)
        self.assertEqual(self.values(), before)
        self.assertTrue((self.state / "lock").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
