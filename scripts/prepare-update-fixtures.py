#!/usr/bin/env python3
"""Build same-key/newer and wrong-key APK fixtures without changing source versions.

Requires JAVA_HOME and ANDROID_HOME. APKs stay under the ignored app/build tree.
Never installs an APK or modifies the production signing key.
"""
from pathlib import Path
import os,re,shutil,subprocess,tempfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'app/build/update-fixtures'
SDK=Path(os.environ['ANDROID_HOME'])
JAVA=Path(os.environ['JAVA_HOME'])/'bin'
BT=SDK/'build-tools/34.0.0'
OUT.mkdir(parents=True,exist_ok=True)

with tempfile.TemporaryDirectory(prefix='luma-update-fixture-') as temporary:
    folder=Path(temporary);project=folder/'project'
    shutil.copytree(ROOT,project,ignore=shutil.ignore_patterns('.git','.gradle','build','artifacts','local.properties'))
    (project/'local.properties').write_text('sdk.dir='+str(SDK)+'\n')
    gradle=(project/'app/build.gradle').read_text()
    version=re.search(r"versionName '([0-9.]+)'",gradle).group(1)
    code=int(re.search(r'versionCode (\d+)',gradle).group(1))
    major,minor,patch=map(int,version.split('.'));next_version=f'{major}.{minor}.{patch+1}'
    gradle=re.sub(r'versionCode \d+',f'versionCode {code+1}',gradle)
    gradle=re.sub(r"versionName '[^']+'",f"versionName '{next_version}'",gradle)
    (project/'app/build.gradle').write_text(gradle)
    log=OUT/'fixture-build.log'
    with log.open('w') as output:
        result=subprocess.run(['./gradlew','--no-daemon',':app:assembleDebug'],cwd=project,stdout=output,stderr=subprocess.STDOUT)
    if result.returncode:raise SystemExit(log.read_text()[-6000:])
    candidate=OUT/'candidate.apk';shutil.copy2(project/'app/build/outputs/apk/debug/app-debug.apk',candidate)
    key=folder/'fixture.keystore'
    subprocess.run([str(JAVA/'keytool'),'-genkeypair','-keystore',str(key),'-storepass','fixture-pass','-keypass','fixture-pass','-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','30','-dname','CN=Luma Updater Fixture'],check=True,capture_output=True)
    subprocess.run([str(BT/'apksigner'),'sign','--ks',str(key),'--ks-pass','pass:fixture-pass','--key-pass','pass:fixture-pass','--out',str(OUT/'wrong-key.apk'),str(candidate)],check=True)
    print('Prepared',next_version,'fixtures in',OUT)
