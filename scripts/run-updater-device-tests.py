#!/usr/bin/env python3
"""Install test-only packages, verify APK fixture transfers, then run TV checks.

Run prepare-update-fixtures.py and build the release/debug/androidTest APKs first.
Requires ANDROID_HOME. The production tv.luma installation is not modified.
"""
from pathlib import Path
import argparse,hashlib,os,re,subprocess

parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True)
parser.add_argument('--classes',default='tv.luma.AppUpdaterUiTest,tv.luma.GlassDialogUiTest,tv.luma.PlayerControlsTest,tv.luma.ResolutionPlaybackTest')
args=parser.parse_args();root=Path(__file__).resolve().parents[1]
adb=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb';base=[str(adb),'-s',args.serial]
for file in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
    subprocess.run(base+['install','-r',str(root/file)],check=True,timeout=120)
subprocess.run(base+['shell','run-as','tv.luma.debug','mkdir','-p','files'],check=True)
fixtures={'candidate':root/'app/build/update-fixtures/candidate.apk','wrong-key':root/'app/build/update-fixtures/wrong-key.apk','current':root/'app/build/outputs/apk/debug/app-debug.apk','other-package':root/'app/build/outputs/apk/release/app-release.apk'}
for name,file in fixtures.items():
    data=file.read_bytes();target='files/update-'+name+'.apk'
    subprocess.run(base+['shell','-T','run-as','tv.luma.debug','dd','of='+target,'bs=65536'],input=data,capture_output=True,check=True,timeout=60)
    remote=subprocess.check_output(base+['shell','run-as','tv.luma.debug','sha256sum',target],text=True).split()[0]
    assert remote==hashlib.sha256(data).hexdigest(),f'Fixture transfer failed: {name}'
    print('Verified fixture:',name,flush=True)
log=root/'artifacts/device-updater-tests-0.7.0.log';log.parent.mkdir(exist_ok=True)
with log.open('w') as output:
    result=subprocess.run(base+['shell','am','instrument','-w','-r','-e','class',args.classes,'tv.luma.debug.test/androidx.test.runner.AndroidJUnitRunner'],stdout=output,stderr=subprocess.STDOUT,text=True,timeout=300)
text=log.read_text();print(text)
if result.returncode or not re.search(r'OK \(\d+ tests?\)',text) or 'FAILURES!!!' in text:raise SystemExit('Device checks failed')
