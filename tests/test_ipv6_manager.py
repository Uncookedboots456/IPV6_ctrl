"""Verify the manager has one mutation path and keeps stdout valid JSON."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ManagerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="ipv6-manager-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.conf = self.root / "conf"
        for name in ("all", "default", "lo", "wlan0"):
            node = self.conf / name / "disable_ipv6"
            node.parent.mkdir(parents=True)
            node.write_text("0\n")
        self.script = self.root / "scripts" / "ipv6_manager.sh"
        self.script.parent.mkdir()
        source = (ROOT / "scripts" / "ipv6_manager.sh").read_text()
        original = 'PROC_IPV6_CONF="/proc/sys/net/ipv6/conf"'
        self.assertEqual(source.count(original), 1)
        self.script.write_text(source.replace(original, f'PROC_IPV6_CONF="{self.conf}"', 1))
        self.bin = self.root / "bin"
        self.bin.mkdir()
        identity = self.bin / "id"
        identity.write_text("#!/bin/sh\nprintf '0\\n'\n")
        identity.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}")

    def run_manager(self, command):
        return subprocess.run(
            [os.environ.get("IPV6_TEST_SHELL", "sh"), str(self.script), command],
            env=self.env, text=True, capture_output=True, timeout=10,
        )

    def test_unavailable_daemon_returns_json_without_fallback_writes(self):
        for command in ("enable", "disable"):
            with self.subTest(command=command):
                result = self.run_manager(command)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(json.loads(result.stdout)["ok"])
                for node in self.conf.glob("*/disable_ipv6"):
                    self.assertEqual(node.read_text(), "0\n")

    def test_status_does_not_start_daemon(self):
        marker = self.root / "started"
        binary = self.root / "ipv6_daemon"
        binary.write_text(f"#!/bin/sh\ntouch '{marker}'\n")
        binary.chmod(0o755)
        result = self.run_manager("json-status")
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertTrue(data["ok"])
        self.assertEqual(data["target"], "unmanaged")
        self.assertFalse(marker.exists())

    def test_read_only_status_reports_mixed_interfaces(self):
        node = self.conf / "wlan0" / "disable_ipv6"
        node.write_text("1\n")
        result = self.run_manager("json-status")
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data["state"], "mixed")
        self.assertFalse(data["target_enforced"])
        self.assertEqual(node.read_text(), "1\n")

    @unittest.skipUnless(shutil.which("cc"), "A Linux C compiler is required")
    def test_action_uses_module_socket_and_returns_json(self):
        client = self.bin / "nc"
        client.write_text('''#!/usr/bin/env python3
import socket, sys
with socket.socket(socket.AF_UNIX) as sock:
    sock.connect(sys.argv[-1])
    sock.sendall(sys.stdin.buffer.read())
    while True:
        data = sock.recv(1024)
        if not data:
            break
        sys.stdout.buffer.write(data)
''')
        client.chmod(0o755)
        binary = self.root / "ipv6_daemon"
        subprocess.run(
            ["cc", "-Wall", "-Wextra", "-Werror", f'-DPROC_CONF_DIR="{self.conf}"',
             str(ROOT / "daemon.c"), "-o", str(binary)],
            check=True, capture_output=True,
        )
        # Own the process for reliable cleanup; manager connects at the module path.
        process = subprocess.Popen([str(binary), str(self.root / "ipv6_daemon.sock")])
        def stop():
            process.terminate()
            process.wait(timeout=3)
        self.addCleanup(stop)
        import time
        deadline = time.monotonic() + 3
        while not (self.root / "ipv6_daemon.sock").exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        result = self.run_manager("disable")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)["target_enforced"])


if __name__ == "__main__":
    unittest.main()
