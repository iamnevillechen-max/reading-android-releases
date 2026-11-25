#!/usr/bin/env python3
"""Create, prepare and remove only this project's disposable Android test device."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
AVD_HOME = ROOT / '.toolchain/reader-test-avd'
NAME = 'reader_test_api35'
SERIAL = 'emulator-5580'
PACKAGE = 'io.multiread.reader.debug'
AVD = AVD_HOME / (NAME + '.avd')
INI = AVD_HOME / (NAME + '.ini')
RECORD = ROOT / '.toolchain/reader-test-device.json'
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk')))
bundled_adb = Path.home() / 'Applications/PhoneMirrorTools/Appium/android-sdk/platform-tools/adb'
ADB = str(Path(os.environ.get('READER_ADB', str(bundled_adb if bundled_adb.is_file() else SDK / 'platform-tools/adb'))))


def run(*arguments, **kwargs):
    return subprocess.run(list(map(str, arguments)), check=True, capture_output=True, timeout=30, **kwargs)


def adb(*arguments, **kwargs):
    return run(ADB, '-s', SERIAL, *arguments, **kwargs)


def own_device():
    data = json.loads(RECORD.read_text())
    if data['project'] != str(ROOT) or data['avd'] != NAME or data['serial'] != SERIAL:
        raise SystemExit('Test-device ownership record does not match this project')
    process = subprocess.run(['ps', '-p', str(data['pid']), '-o', 'command='], capture_output=True, text=True)
    if '-avd ' + NAME not in process.stdout or '-port 5580' not in process.stdout:
        raise SystemExit('The recorded test process is no longer the owner of this device')
    if adb('emu', 'avd', 'name').stdout.decode().splitlines()[0] != NAME:
        raise SystemExit('The emulator on this port belongs to another task')
    return data


def start():
    devices = {line.split()[0] for line in run(ADB, 'devices').stdout.decode().splitlines()[1:] if line.strip()}
    if SERIAL in devices:
        own_device()
        print(json.dumps({'serial': SERIAL, 'reused': True}))
        return
    if AVD.exists() or INI.exists() or AVD_HOME.is_symlink():
        raise SystemExit('An existing test checkout needs inspection; no files changed')
    image = SDK / 'system-images/android-35/default/arm64-v8a'
    if not image.is_dir():
        raise SystemExit('Install the API35 default arm64-v8a system image first')
    AVD.mkdir(parents=True)
    config = {'AvdId': NAME, 'avd.ini.encoding': 'UTF-8', 'abi.type': 'arm64-v8a',
              'hw.cpu.arch': 'arm64', 'image.sysdir.1': str(image) + '/',
              'tag.id': 'default', 'tag.display': 'Default', 'target': 'android-35',
              'hw.cpu.ncore': '2', 'hw.ramSize': '1536', 'hw.lcd.width': '1080',
              'hw.lcd.height': '1920', 'hw.lcd.density': '420', 'hw.keyboard': 'yes',
              'hw.mainKeys': 'no', 'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'swiftshader',
              'disk.dataPartition.size': '1536M', 'showDeviceFrame': 'no'}
    (AVD / 'config.ini').write_text(''.join(k + '=' + v + '\n' for k, v in config.items()))
    INI.write_text('avd.ini.encoding=UTF-8\npath=' + str(AVD) + '\ntarget=android-35\n')
    environment = os.environ.copy()
    environment.update(ANDROID_AVD_HOME=str(AVD_HOME), ANDROID_SDK_ROOT=str(SDK), ANDROID_HOME=str(SDK))
    with (ROOT / '.toolchain/reader-test-device.log').open('w') as log:
        process = subprocess.Popen([str(SDK / 'emulator/emulator'), '-avd', NAME, '-port', '5580',
            '-memory', '1536', '-cores', '2', '-no-window', '-no-snapshot', '-no-boot-anim',
            '-no-audio', '-no-metrics', '-gpu', 'swiftshader'], env=environment,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    RECORD.write_text(json.dumps({'project': str(ROOT), 'serial': SERIAL, 'avd': NAME,
                                 'pid': process.pid, 'active': True}, indent=2) + '\n')
    print(json.dumps({'serial': SERIAL, 'pid': process.pid, 'started': True}))


def prepare():
    own_device()
    # Run before the first application launch: never replace an existing test library.
    exists = subprocess.run([ADB, '-s', SERIAL, 'shell', 'run-as', PACKAGE, 'test', '-f',
                             'databases/legado.db'], capture_output=True).returncode == 0
    if exists:
        raise SystemExit('Prepare requires a new test installation without an existing database')
    gradle = (ROOT / 'upstream/app/build.gradle').read_text()
    version = re.search(r'readerVersionCode.*?"(\d+)"', gradle).group(1)
    source = (ROOT / 'upstream/app/src/main/java/io/legado/app/help/source/VerifiedSourceBootstrap.kt').read_text()
    pack = re.search(r'PACK_VERSION = (\d+)', source).group(1)
    files = {'local.xml': '<map><long name="appVersionCode" value="' + version +
             '"/><boolean name="privacyPolicyOk" value="true"/></map>',
             'reader_verified_sources_v1.xml': '<map><int name="packVersion" value="' + pack + '"/></map>'}
    adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'shared_prefs')
    for name, content in files.items():
        adb('shell', 'run-as', PACKAGE, 'sh', '-c', shlex.quote('cat > shared_prefs/' + name),
            input=('<?xml version="1.0" encoding="utf-8"?>\n' + content + '\n').encode())
    print(json.dumps({'serial': SERIAL, 'emptyFixturePrepared': True, 'versionCode': int(version)}))


def stop():
    data = own_device()
    if AVD_HOME.is_symlink() or AVD.is_symlink() or INI.is_symlink():
        raise SystemExit('Inspect unexpected test-device symlink before cleanup')
    allocated = int(run('du', '-sk', AVD).stdout.split()[0]) * 1024
    adb('emu', 'kill')
    for _ in range(75):
        process = subprocess.run(['ps', '-p', str(data['pid']), '-o', 'command='], capture_output=True)
        if not process.stdout.strip():
            break
        time.sleep(.2)
    else:
        raise SystemExit('Test process is still active; AVD files retained')
    shutil.rmtree(AVD)
    INI.unlink()
    data.update(active=False, allocatedBytesRemoved=allocated)
    RECORD.write_text(json.dumps(data, indent=2) + '\n')
    print(json.dumps({'serial': SERIAL, 'stoppedAndRemoved': True, 'allocatedBytesRemoved': allocated,
                      'otherDevicesAndSharedSdkUntouched': True}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'prepare', 'stop'])
    options = parser.parse_args()
    {'start': start, 'prepare': prepare, 'stop': stop}[options.action]()
