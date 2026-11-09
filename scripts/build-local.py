#!/usr/bin/env python3
"""Offline SDK build using the exact dependency/resource caches from the last Gradle build.

Use ./gradlew for ordinary builds. This fallback needs an existing successful
Gradle build and avoids Gradle's socket listener in restricted sessions.
"""
from pathlib import Path
import os, re, shutil, subprocess, zipfile, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
SDK=Path(os.environ.get('ANDROID_HOME', Path.home()/'Android/Sdk'))
JAVA=Path(os.environ.get('JAVA_HOME', Path.home()/'.local/share/rea/jdk-21.0.13+9'))/'bin'
BT=SDK/'build-tools/34.0.0'
OUT=ROOT/'app/build/local-port'
OUT.mkdir(parents=True,exist_ok=True)
ENV=dict(os.environ,JAVA_HOME=str(JAVA.parent),PATH=str(JAVA)+os.pathsep+os.environ.get("PATH",""))

def run(args):
    if args[0]==BT/'d8':
        builder=next((Path.home()/'.gradle/caches/modules-2/files-2.1/com.android.tools.build/builder/8.5.2').rglob('builder-8.5.2.jar'))
        args=[JAVA/'java','-Xmx2G','-cp',builder,'com.android.tools.r8.D8']+args[1:]
    print('Running', Path(str(args[0])).name, flush=True)
    subprocess.run([str(a) for a in args],cwd=ROOT,env=ENV,check=True)

def dependencies(variant='debug-artifact'):
    p=ROOT/'app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel'/f'{variant}-libraries.xml'
    if not p.exists():
        raise SystemExit('Run a normal Gradle build once to populate the dependency model.')
    return [Path(j) for lib in ET.parse(p).getroot() for j in lib.get('jars','').split(';') if j]

def compile_app():
    classes=OUT/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir()
    jars=dependencies()+[ROOT/'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/release/processReleaseResources/R.jar']
    run([JAVA/'javac','-source','17','-target','17','-encoding','UTF-8','-classpath',os.pathsep.join(map(str,[SDK/'platforms/android-34/android.jar']+jars)),'-d',classes,*sorted((ROOT/'app/src/main/java').rglob('*.java'))])
    return classes,jars

def build():
    classes,jars=compile_app()
    # Compile pinned library resources, followed by the application's overlays.
    model=ROOT/'app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel/debug-artifact-libraries.xml'
    resourceZips=[]
    for i,lib in enumerate(ET.parse(model).getroot()):
        folder=Path(lib.get('folder',''))/'res'
        if lib.get('folder') and folder.exists() and any(folder.rglob('*.*')):
            target=OUT/f'library-res-{i}.zip';run([BT/'aapt2','compile','--dir',folder,'-o',target]);resourceZips.append(target)
    target=OUT/'app-res.zip';run([BT/'aapt2','compile','--dir',ROOT/'app/src/main/res','-o',target]);resourceZips.append(target)
    manifest=(ROOT/'app/build/intermediates/packaged_manifests/release/processReleaseManifestForPackage/AndroidManifest.xml').read_text()
    gradle=(ROOT/'app/build.gradle').read_text()
    version=re.search(r"versionName '([^']+)'",gradle).group(1);code=re.search(r'versionCode (\d+)',gradle).group(1)
    # Reuse merged dependency declarations, with the current activity attributes.
    namespace='{http://schemas.android.com/apk/res/android}'
    ET.register_namespace('android','http://schemas.android.com/apk/res/android')
    tree=ET.fromstring(manifest)
    source=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
    for current in source.findall('./application/activity'):
        name=current.get(namespace+'name','')
        full='tv.luma'+name if name.startswith('.') else name
        for merged in tree.findall('./application/activity'):
            if merged.get(namespace+'name')==full:
                for attr,value in current.attrib.items():
                    if attr!=namespace+'name':merged.set(attr,value)
    manifest=ET.tostring(tree,encoding='unicode')
    manifest=re.sub(r'android:versionCode="[^"]+"',f'android:versionCode="{code}"',manifest)
    manifest=re.sub(r'android:versionName="[^"]+"',f'android:versionName="{version}"',manifest)
    (OUT/'AndroidManifest.xml').write_text(manifest)
    resources=OUT/'resources.ap_'
    ids=ROOT/'app/build/intermediates/stable_resource_ids_file/release/processReleaseResources/stableIds.txt'
    if not ids.exists():raise SystemExit('Missing Gradle stable resource IDs')
    run([BT/'aapt2','link','-o',resources,'--manifest',OUT/'AndroidManifest.xml','-I',SDK/'platforms/android-34/android.jar','--stable-ids',ids,'--auto-add-overlay',*sum((['-R',p] for p in resourceZips),[])])
    appjar=OUT/'app.jar'
    with zipfile.ZipFile(appjar,'w') as z:
        for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes))
    dex=OUT/'dex';shutil.rmtree(dex,ignore_errors=True);dex.mkdir()
    # Compile app classes with the SDK's D8. Library dex files were compiled by
    # the last Gradle build against these same unchanged pinned dependencies.
    run([BT/'d8','--release','--min-api','26','--lib',SDK/'platforms/android-34/android.jar',*sum((['--classpath',p] for p in jars),[]),'--output',dex,appjar])
    unsigned=OUT/'unsigned.apk'
    with zipfile.ZipFile(resources) as reszip, zipfile.ZipFile(ROOT/'artifacts/Luma-TV-0.1.0.apk') as old, zipfile.ZipFile(unsigned,'w',zipfile.ZIP_DEFLATED) as z:
        for n in reszip.namelist():
            # Android 11+ rejects a compressed resource table for target SDK 30+.
            z.writestr(n,reszip.read(n),compress_type=zipfile.ZIP_STORED if n=='resources.arsc' else zipfile.ZIP_DEFLATED)
        olddex=[n for n in old.namelist() if re.fullmatch(r'classes\d*\.dex',n)]
        # D8 merge below replaces all old app classes; no old app dex is copied.
        for n in old.namelist():
            if n.startswith('res/') or re.match(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$',n,re.I) or n in ('AndroidManifest.xml','resources.arsc') or n in olddex:continue
            z.writestr(n,old.read(n))
    # Gradle's external_libs_dex contains unchanged dependency code.
    external=sorted((ROOT/'app/build/intermediates/external_libs_dex/release').rglob('*.dex'))
    if not external:raise SystemExit('Missing dependency-only dex from prior Gradle build')
    # Add the old generated app/library R classes if not in the dependency dex.
    rjar=ROOT/'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/release/processReleaseResources/R.jar'
    mergedDex=OUT/'merged-dex';shutil.rmtree(mergedDex,ignore_errors=True);mergedDex.mkdir()
    run([BT/'d8','--release','--min-api','26','--lib',SDK/'platforms/android-34/android.jar','--output',mergedDex,*sorted(dex.glob('*.dex')),*external,rjar])
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(mergedDex.glob('*.dex')):z.write(p,p.name)
    aligned=OUT/'aligned.apk';run([BT/'zipalign','-f','4',unsigned,aligned])
    apk=ROOT/f'artifacts/Luma-TV-{version}.apk'
    run([BT/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,aligned])
    run([BT/'apksigner','verify','--verbose',apk])
    run([BT/'aapt','dump','badging',apk])
    print('APK:',apk)

if __name__=='__main__':
    import sys
    if '--compile-only' in sys.argv:compile_app()
    else:build()
