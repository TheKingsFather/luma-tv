#!/usr/bin/env python3
"""Run protocol, Live and injected provider fixtures without opening network sockets."""
from pathlib import Path
import os,subprocess
root=Path(__file__).resolve().parents[1]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
jars=[next((cache/g/a/v).rglob('*.jar')) for g,a,v in [('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3'),('org.json','json','20240303')]]
out=root/'app/build/local-port/unit-tests';out.mkdir(parents=True,exist_ok=True)
java=Path(os.environ.get('JAVA_HOME',Path.home()/'.local/share/rea/jdk-21.0.13+9'))/'bin'
cp=os.pathsep.join(map(str,jars))
sources=[root/'app/src/main/java/tv/luma'/n for n in ['Models.java','StremioClient.java']]+list((root/'app/src/test/java/tv/luma').glob('*.java'))
subprocess.run([str(java/'javac'),'-encoding','UTF-8','-cp',cp,'-d',str(out),*map(str,sources)],check=True)
raise SystemExit(subprocess.run([str(java/'java'),'-cp',str(out)+os.pathsep+cp,'org.junit.runner.JUnitCore','tv.luma.ProtocolTest','tv.luma.LiveCatalogTest','tv.luma.ProviderContractTest']).returncode)
