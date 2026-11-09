#!/usr/bin/env python3
"""Check APK integrity, update signing, stable resources and unchanged library inventory."""
from pathlib import Path
import os,re,struct,subprocess,zipfile,hashlib
root=Path(__file__).resolve().parents[1]
bt=Path(os.environ.get('ANDROID_HOME',Path.home()/'Android/Sdk'))/'build-tools/34.0.0'
env=dict(os.environ);env['PATH']=str(Path(os.environ.get('JAVA_HOME',Path.home()/'.local/share/rea/jdk-21.0.13+9'))/'bin')+os.pathsep+env['PATH']
old=root/'artifacts/Luma-TV-0.3.0.apk';new=root/'artifacts/Luma-TV-0.4.0.apk'
certs=[];classes=[];resources=[]
for apk in [old,new]:
 cert=subprocess.check_output([str(bt/'apksigner'),'verify','--print-certs',str(apk)],env=env,text=True);certs.append(re.search('certificate SHA-256 digest: ([0-9a-f]+)',cert).group(1))
 res=subprocess.check_output([str(bt/'aapt'),'dump','resources',str(apk)],text=True,errors='replace');resources.append(dict(re.findall(r'(?:spec )?resource (0x[0-9a-f]+) ([^: ]+:[^: ]+):',res)))
 desc=[]
 with zipfile.ZipFile(apk) as z:
  assert z.testzip() is None
  table=z.getinfo('resources.arsc')
  assert table.compress_type==zipfile.ZIP_STORED, 'Android requires resources.arsc to be uncompressed'
  with apk.open('rb') as f:
   f.seek(table.header_offset);header=f.read(30);nameSize,extraSize=struct.unpack_from('<HH',header,26)
  assert (table.header_offset+30+nameSize+extraSize)%4==0, 'Resource table is not 4-byte aligned'
  for n in z.namelist():
   if re.fullmatch(r'classes\d*\.dex',n):
    path=root/'app/build/local-port'/('old-'+n if apk==old else 'new-'+n);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(z.read(n));dump=subprocess.check_output([str(bt/'dexdump'),'-f',str(path)],text=True,errors='replace');desc.extend(re.findall(r"Class descriptor\s*:\s*'([^']+)'",dump))
 assert desc and len(desc)==len(set(desc)), 'Missing or duplicate classes'
 classes.append(set(desc))
assert certs[0]==certs[1], 'Signing key changed'
assert resources[0] and set(resources[0].values())<=set(resources[1].values()), 'Missing existing resources'
missing={c for c in classes[0]-classes[1] if not c.startswith('Ltv/luma/')}
assert not missing, 'Missing library classes: '+str(missing)
assert 'Ltv/luma/PlayerPanel;' in classes[1]
assert 'Ltv/luma/PosterCard;' in classes[1] and 'Ltv/luma/ExitDialog;' in classes[1]
assert 'Ltv/luma/UiFixtureActivity;' not in classes[1], 'Debug fixture activity must not ship in the release APK'
report='Same signing certificate as 0.3.0\nAll original resource names retained: '+str(len(resources[0]))+'; added gradient resources: '+str(len(resources[1])-len(resources[0]))+'\nUnique packaged classes: '+str(len(classes[1]))+'\nAll prior third-party library classes retained\nZIP CRC checks passed\nSHA256: '+hashlib.sha256(new.read_bytes()).hexdigest()+'\n'
print(report);(root/'artifacts/package-checks-0.4.0.log').write_text(report)
