"""Run a fake-procfs Android daemon build over adb; never install a module."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
DEVICE_ROOT = "/data/local/tmp/ipv6-ctrl-smoke"


def adb(*args):
    result = subprocess.run(["adb", *args], text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout or f"adb failed: {args}")
    return result.stdout


def main(binary):
    data = binary.read_bytes()
    if b"/proc/sys/net/ipv6/conf" in data or (DEVICE_ROOT + "/conf").encode() not in data:
        raise ValueError("Use a daemon built with the isolated PROC_CONF_DIR, not the production binary")
    adb("get-state")
    adb("shell", "su", "-c", f"test ! -e {DEVICE_ROOT}")
    real_nodes = " ".join(f"/proc/sys/net/ipv6/conf/{name}/disable_ipv6" for name in ("all", "default", "lo", "wlan0"))
    before = adb("shell", "su", "-c", "cat " + real_nodes)
    try:
        with tempfile.TemporaryDirectory(prefix="ipv6-android-") as temp:
            stage = Path(temp)
            shutil.copyfile(binary, stage / "ipv6_daemon")
            (stage / "android_smoke.sh").write_bytes((ROOT / "tests/android_smoke.sh").read_text().encode())
            (stage / "scripts").mkdir()
            source = (ROOT / "scripts/ipv6_manager.sh").read_text()
            original = 'PROC_IPV6_CONF="/proc/sys/net/ipv6/conf"'
            if source.count(original) != 1:
                raise ValueError("Expected exactly one manager procfs path")
            source = source.replace(original, f'PROC_IPV6_CONF="{DEVICE_ROOT}/conf"', 1)
            (stage / "scripts/ipv6_manager.sh").write_bytes(source.encode())
            adb("push", str(stage), DEVICE_ROOT)
            adb("shell", "su", "-c", f"chmod 755 {DEVICE_ROOT}/ipv6_daemon")
            print(adb("shell", "su", "-c", f"sh {DEVICE_ROOT}/android_smoke.sh").strip())
    finally:
        # Fixed, dedicated test directory; it was required to be absent above.
        adb("shell", "su", "-c", f"rm -r {DEVICE_ROOT}")
        after = adb("shell", "su", "-c", "cat " + real_nodes)
        if before != after:
            raise RuntimeError("Observed real sysctl values changed during the isolated test; investigate")
    print("PASS: observed real sysctl values preserved; temporary test files removed")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tests/run_android_smoke.py ISOLATED_ANDROID_BINARY")
    main(Path(sys.argv[1]))
