"""Check the installed probe's loopback results; does not change module settings."""
import argparse
import json
import subprocess
import time


def adb(*args):
    return subprocess.check_output(['adb', *args], encoding='utf-8', errors='replace')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['SYSTEM', 'IPV4_FIRST', 'IPV4_ONLY'], required=True)
    parser.add_argument('--unselected', action='store_true', help='expect scope injection but no policy target')
    parser.add_argument('--unhooked', action='store_true', help='expect no LSPosed injection')
    args = parser.parse_args()
    package = 'dev.ipv6ctrl.probe'
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'am', 'start', '-W', '-n', package + '/.ProbeActivity')
    deadline = time.monotonic() + 20
    result = None
    while time.monotonic() < deadline:
        pid = adb('shell', 'pidof', package).strip()
        logs = adb('logcat', '-d', '--pid=' + pid, '-v', 'raw', '-s', 'IPV6CtrlProbe:I', '*:S')
        for line in logs.splitlines():
            if line.startswith('{'):
                result = json.loads(line)
        if result is not None:
            break
        time.sleep(.3)
    assert result is not None, 'probe did not report within 20 seconds'
    marker = 'not-injected' if args.unhooked else f'api=102;mode={args.mode};selected={str(not args.unselected).lower()}'
    assert result.get('hook') == marker, result
    active = not args.unselected and not args.unhooked
    mode = args.mode if active else 'SYSTEM'
    expected = dict(ipv4=True, ipv6=mode != 'IPV4_ONLY', firstFamily=6 if mode == 'SYSTEM' else 4,
                    v4Count=1, v6Count=0 if mode == 'IPV4_ONLY' else 1)
    assert 'error' not in result and all(result.get(k) == v for k, v in expected.items()), result
    print('PASS', json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
