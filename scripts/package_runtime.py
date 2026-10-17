"""Package only the new Runtime module; never include legacy service/WebUI files."""
import pathlib
import struct
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    binary = ROOT / 'module/ipv6_daemon'
    header = binary.read_bytes()[:20]
    if header[:6] != b'\x7fELF\x02\x01' or struct.unpack('<H', header[18:20])[0] != 183:
        raise SystemExit('Build the arm64 runtime binary first')
    output = ROOT / 'android/build/runtime'
    output.mkdir(parents=True, exist_ok=True)
    archive = output / 'IPV6-Ctrl-runtime-2.1-dev-arm64.zip'
    files = ['module.prop', 'protocol', 'ipv6_daemon', 'service.sh', 'customize.sh', 'uninstall.sh']
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
        for name in files:
            package.write(ROOT / 'module' / name, name)
        package.write(ROOT / 'android/app/src/main/assets/system-ipv6.sh', 'control.sh')
        package.write(ROOT / 'LICENSE', 'LICENSE')
        package.writestr('skip_mount', '')
    with zipfile.ZipFile(archive) as package:
        assert package.testzip() is None
        assert package.read('control.sh') == (ROOT / 'android/app/src/main/assets/system-ipv6.sh').read_bytes()
        assert package.read('ipv6_daemon') == binary.read_bytes()
    print(archive)


if __name__ == '__main__':
    main()
