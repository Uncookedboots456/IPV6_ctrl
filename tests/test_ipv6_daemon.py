"""Compile and exercise the real daemon against private temporary files only."""

import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("cc"), "A Linux C compiler is required")
class DaemonTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="ipv6-daemon-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.conf = self.root / "conf"
        for name in ("all", "default", "lo", "wlan0", "rmnet0"):
            self.node(name).parent.mkdir(parents=True)
            self.node(name).write_text("0\n")
        self.binary = self.root / "daemon"
        subprocess.run(
            ["cc", "-Wall", "-Wextra", "-Werror", "-O2",
             f'-DPROC_CONF_DIR="{self.conf}"', str(ROOT / "daemon.c"),
             "-o", str(self.binary)], check=True, capture_output=True,
        )
        self.sock = self.root / "daemon.sock"
        self.process = subprocess.Popen([str(self.binary), str(self.sock)])
        self.addCleanup(self.stop)
        deadline = time.monotonic() + 3
        while not self.sock.exists() and time.monotonic() < deadline:
            self.assertIsNone(self.process.poll(), "daemon exited during startup")
            time.sleep(0.01)
        self.assertTrue(self.sock.exists(), "daemon socket was not created")

    def node(self, name):
        return self.conf / name / "disable_ipv6"

    def stop(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)

    def request(self, command):
        with socket.socket(socket.AF_UNIX) as client:
            client.settimeout(3)
            client.connect(str(self.sock))
            client.sendall(command.encode() + b"\n")
            data = b""
            while True:
                chunk = client.recv(1024)
                if not chunk:
                    return json.loads(data)
                data += chunk

    def test_single_instance_keeps_original_socket_and_owner(self):
        inode = self.sock.stat().st_ino
        other = subprocess.run([str(self.binary), str(self.sock)], timeout=3)
        self.assertNotEqual(other.returncode, 0)
        self.assertEqual(self.sock.stat().st_ino, inode)
        self.assertTrue(self.request("GET_STATUS")["ok"])
        self.assertEqual(self.sock.stat().st_mode & 0o777, 0o600)

    def test_no_self_write_loop_or_blocking_cooldown(self):
        self.assertTrue(self.request("DISABLE")["target_enforced"])
        written = {p: p.stat().st_mtime_ns for p in self.conf.glob("*/disable_ipv6")}
        time.sleep(3.2)
        start = time.monotonic()
        self.assertTrue(self.request("GET_STATUS")["target_enforced"])
        self.assertLess(time.monotonic() - start, 1)
        self.assertEqual({p: p.stat().st_mtime_ns for p in written}, written)

    def test_existing_file_and_legacy_listener_are_preserved(self):
        other_path = self.root / "other.sock"
        other_path.write_text("keep this file")
        result = subprocess.run([str(self.binary), str(other_path)], timeout=3)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(other_path.read_text(), "keep this file")
        other_path.unlink()
        with socket.socket(socket.AF_UNIX) as listener:
            listener.bind(str(other_path))
            listener.listen(1)
            inode = other_path.stat().st_ino
            result = subprocess.run([str(self.binary), str(other_path)], timeout=3)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(other_path.stat().st_ino, inode)

    def test_idle_client_is_bounded(self):
        with socket.socket(socket.AF_UNIX) as idle:
            idle.connect(str(self.sock))
            start = time.monotonic()
            self.assertTrue(self.request("GET_STATUS")["ok"])
            self.assertLess(time.monotonic() - start, 2.5)

    def test_individual_drift_and_new_interfaces_are_detected(self):
        self.assertTrue(self.request("DISABLE")["ok"])
        self.node("rmnet0").write_text("0\n")
        self.node("new0").parent.mkdir()
        self.node("new0").write_text("0\n")
        status = self.request("GET_STATUS")
        self.assertEqual(status["state"], "mixed")
        self.assertFalse(status["target_enforced"])
        deadline = time.monotonic() + 5
        while self.node("new0").read_text()[0] != "1" and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertEqual(self.node("rmnet0").read_text()[0], "1")
        self.assertEqual(self.node("new0").read_text()[0], "1")

    def test_failed_interface_write_is_not_reported_as_success(self):
        self.node("rmnet0").unlink()
        self.node("rmnet0").symlink_to("/dev/full")
        self.assertFalse(self.request("DISABLE")["ok"])
        self.assertFalse(self.request("GET_STATUS")["ok"])

    def test_shutdown_removes_socket_and_allows_restart(self):
        self.stop()
        self.assertEqual(self.process.returncode, 0)
        self.assertFalse(self.sock.exists())
        self.process = subprocess.Popen([str(self.binary), str(self.sock)])
        deadline = time.monotonic() + 3
        while not self.sock.exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(self.request("GET_STATUS")["ok"])


if __name__ == "__main__":
    unittest.main()
