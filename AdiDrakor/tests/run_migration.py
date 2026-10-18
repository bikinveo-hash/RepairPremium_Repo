#!/usr/bin/env python3
"""Compile each plugin in isolation and replay upstream H5 fixtures against every local port.
Moviebox test sources are read only and never packaged. No cross-module production dependencies.
"""
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile

from verify_integrity import verify

ROOT = Path(__file__).resolve().parents[2]
# Reuse the audited SDK/test dependency setup and run the unchanged original regression suite.
env = runpy.run_path(str(ROOT / "AdiDrakor/tests/upstream/Moviebox/tests/run_regression.py"))
verify(ROOT, env)
MODULES = {"AdiDrakor": "com.AdiDrakor", "AdiFilmSemi": "com.michat88",
           "AdiXtream": "com.adixtream", "Adicinemax21": "com.Adicinemax21"}
isolation = tempfile.TemporaryDirectory(prefix="four-plugin-proxy-")
isolation_jars = []
for module, package in MODULES.items():
    source = sorted((ROOT / module / "src/main/kotlin").rglob("*.kt"))
    production = "\n".join(p.read_text() for p in source)
    for forbidden in ["invokeIdlix", "invokeVidSrc", "MovieBoxV2Shared", "PrefetchProvider", "PlaybackFixedProvider", "play-info"]:
        # Production comments may mention the retired endpoint; no quoted request path may use it.
        assert forbidden not in production if forbidden != "play-info" else 'subject-api/play-info' not in production
    assert production.count("@CloudstreamPlugin") == 1
    assert production.count("registerMainAPI(") == 1
    assert "class OppaPlayback : MainAPI" not in production
    assert "class IdlixSource : MainAPI" not in production
    assert f"registerMainAPI({module}())" in production
    assert 'srcDir(' not in (ROOT / module / "build.gradle.kts").read_text()
    assert "com.Moviebox" not in production
    for other, other_package in MODULES.items():
        if other != module:
            assert f"import {other_package}." not in production
    # Whole-file token parity replaces whitespace-sensitive snippet equality.
    # Every executable token/string is pinned to the approved source commit;
    # every original functional fixture below still runs against actual production.
    assert not (ROOT / module / "src/main/kotlin/com/lagradost/cloudstream3/utils").exists()
    with tempfile.TemporaryDirectory(prefix=f"{module}-h5-") as tmp:
        tmp = Path(tmp)
        env["compile_to"](tmp / "production.jar", source)
        retained=Path(isolation.name) / (module+".jar")
        shutil.copyfile(tmp / "production.jar", retained)
        isolation_jars.append(retained)
        tests = []
        for filename in ["PlaybackRegression.kt", "PlaybackIntegration.kt"]:
            text=(ROOT / "AdiDrakor/tests/upstream/Moviebox/tests" / filename).read_text().replace("package com.Moviebox",f"package {package}")
            text=text.replace("MovieBoxProvider()",f'{module}MovieboxEngine("{module}")')
            text=text.replace("MovieBoxProvider",module+"MovieboxEngine").replace("MovieboxPlayback",module+"MovieboxPlayback")
            target=tmp / filename;target.write_text(text);tests.append(target)
        text=(ROOT / "AdiDrakor/tests/MappingRegression.kt.in").read_text()
        for key,value in {"PACKAGE":package,"MATCHER":module+"MovieboxMapping","PLAYBACK":module+"MovieboxPlayback","PROVIDER":module}.items():
            text=text.replace(key,value)
        target=tmp / "MappingRegression.kt";target.write_text(text);tests.append(target)
        text=(ROOT / "AdiDrakor/tests/MediaRecoveryRegression.kt.in").read_text()
        for key,value in {"PACKAGE":package,"V4MATCHER":module+"MovieboxMapping","V4PLAYBACK":module+"MovieboxPlayback","V4MEDIA":module+"MovieboxMedia","V4LOG":module+"MovieboxDiagnostics","V4ENGINE":module+"MovieboxEngine","V4PROVIDER":module}.items():
            text=text.replace(key,value)
        target=tmp / "MediaRecoveryRegression.kt";target.write_text(text);tests.append(target)
        text=(ROOT / "AdiDrakor/tests/MultiSourceRegression.kt.in").read_text().replace("PACKAGE",package)
        target=tmp / "MultiSourceRegression.kt";target.write_text(text);tests.append(target)
        text=(ROOT / "AdiDrakor/tests/ProxyRegression.kt.in").read_text().replace("PACKAGE",package)
        target=tmp / "ProxyRegression.kt";target.write_text(text);tests.append(target)
        tests += sorted((ROOT / "AdiDrakor/tests/upstream/Moviebox/tests/shims").glob("*.kt"))
        tests += sorted((ROOT / "AdiDrakor/tests/shims").glob("*.kt"))
        # Compile fixtures separately against the actual production jar. Friend access only
        # exposes internal ports to tests; test shims precede the SDK at runtime.
        subprocess.run(["java","-cp",env["compiler_cp"],"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib","-no-reflect","-jvm-target","11","-Xfriend-paths="+str(tmp / "production.jar"),
            "-classpath",str(tmp / "production.jar")+os.pathsep+env["classpath"],
            "-d",str(tmp / "tests.jar"),*map(str,tests)],check=True)
        for entry in ["sources.MultiSourceRegressionKt","sources.ProxyRegressionKt","PlaybackRegressionKt","PlaybackIntegrationKt","MappingRegressionKt","MediaRecoveryRegressionKt"]:
            subprocess.run(["java","-Dmigration.fixtures="+str(ROOT / "AdiDrakor/tests/fixtures"),"-cp",str(tmp / "tests.jar")+os.pathsep+str(tmp / "production.jar")+os.pathsep+env["classpath"],package+"."+entry],check=True,timeout=150)
    print(f"PASS {module}: isolated SDK compile, original H5 parity, TMDB mapping and registered MainAPI",flush=True)
isolation_test=Path(isolation.name) / "isolation-tests.jar"
env["compile_to"](isolation_test, [ROOT / "AdiDrakor/tests/ProxyIsolation.kt"])
subprocess.run(["java","-cp",os.pathsep.join(map(str,[isolation_test,*isolation_jars]))+os.pathsep+env["classpath"],
    "ProxyIsolationKt"],check=True,timeout=30)
isolation.cleanup()
print("ALL FOUR H5 + MULTISOURCE REGRESSIONS PASS; live service and Android device playback NOT_TESTED")
