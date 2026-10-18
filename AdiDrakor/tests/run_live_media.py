#!/usr/bin/env python3
"""Optional real Kotlin transport probe. First run run_migration.py for SDK/test jars.
Output is sanitized JSON lines; successful H5 JSON is never a device playback PASS.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.parse

ROOT=Path(__file__).resolve().parents[2]
CACHE=Path(os.environ.get('MOVIEBOX_TEST_CACHE',Path(tempfile.gettempdir())/'moviebox-playback-tests'))
compiler_names=['compiler.jar','stdlib.jar','reflect.jar','script.jar','coroutines.jar','annotations.jar']
assert all((CACHE/n).exists()for n in compiler_names),'Run python3 AdiDrakor/tests/run_migration.py first'
compiler=os.pathsep.join(str(CACHE/n)for n in compiler_names)
classpath=os.pathsep.join(str(p)for p in sorted(CACHE.glob('*.jar'))if p.name not in ['compiler.jar','script.jar','production.jar','tests.jar'])
with tempfile.TemporaryDirectory(prefix='adi-live-media-')as tmp:
    tmp=Path(tmp);probe=tmp/'LiveMediaProbe.kt'
    probe.write_text((ROOT/'AdiDrakor/tests/LiveMediaProbe.kt.in').read_text())
    sources=list((ROOT/'AdiDrakor/src/main/kotlin').rglob('*.kt'))+list((ROOT/'AdiDrakor/tests/upstream/Moviebox/tests/shims').glob('*.kt'))+[probe]
    subprocess.run(['java','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-classpath',classpath,'-d',str(tmp/'probe.jar'),*map(str,sources)],check=True)
    options=[]
    # Use this environment's normal network route, never introduce a geographic proxy.
    proxy=urllib.parse.urlsplit(os.environ.get('https_proxy',''))
    if proxy.hostname and proxy.port:options=[f'-Dhttps.proxyHost={proxy.hostname}',f'-Dhttps.proxyPort={proxy.port}',f'-Dhttp.proxyHost={proxy.hostname}',f'-Dhttp.proxyPort={proxy.port}']
    subprocess.run(['java',*options,'-cp',str(tmp/'probe.jar')+os.pathsep+classpath,'com.AdiDrakor.LiveMediaProbeKt',*sys.argv[1:]],check=True,timeout=90)
