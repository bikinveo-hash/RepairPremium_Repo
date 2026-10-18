#!/usr/bin/env python3
"""Compile production separately, then run JVM schema and local HTTP integration fixtures.
Only test tooling is downloaded. Nothing here is packaged into the plugin.
Optional CLOUDSTREAM_JAR and MOVIEBOX_TEST_CACHE select an existing SDK jar/cache.
"""
import concurrent.futures
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

MODULE = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("MOVIEBOX_TEST_CACHE", Path(tempfile.gettempdir()) / "moviebox-playback-tests"))
CACHE.mkdir(parents=True, exist_ok=True)
MAVEN = "https://repo.maven.apache.org/maven2/"
ARTIFACTS = {
    "compiler.jar": "org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.0/kotlin-compiler-embeddable-2.4.0.jar",
    "stdlib.jar": "org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.jar",
    "reflect.jar": "org/jetbrains/kotlin/kotlin-reflect/2.4.0/kotlin-reflect-2.4.0.jar",
    "script.jar": "org/jetbrains/kotlin/kotlin-script-runtime/2.4.0/kotlin-script-runtime-2.4.0.jar",
    "annotations.jar": "org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar",
    "coroutines.jar": "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar",
    "json.jar": "org/json/json/20251224/json-20251224.jar",
    "android.jar": "com/google/android/android/4.1.1.4/android-4.1.1.4.jar",
    "jsoup.jar": "org/jsoup/jsoup/1.22.2/jsoup-1.22.2.jar",
    "jackson-annotations.jar": "com/fasterxml/jackson/core/jackson-annotations/2.13.1/jackson-annotations-2.13.1.jar",
    "jackson-core.jar": "com/fasterxml/jackson/core/jackson-core/2.13.1/jackson-core-2.13.1.jar",
    "jackson-databind.jar": "com/fasterxml/jackson/core/jackson-databind/2.13.1/jackson-databind-2.13.1.jar",
    "jackson-kotlin.jar": "com/fasterxml/jackson/module/jackson-module-kotlin/2.13.1/jackson-module-kotlin-2.13.1.jar",
    "okhttp.jar": "com/squareup/okhttp3/okhttp-jvm/5.3.2/okhttp-jvm-5.3.2.jar",
    "okio.jar": "com/squareup/okio/okio-jvm/3.16.4/okio-jvm-3.16.4.jar",
    "serialization-core.jar": "org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.11.0/kotlinx-serialization-core-jvm-1.11.0.jar",
    "serialization-json.jar": "org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.11.0/kotlinx-serialization-json-jvm-1.11.0.jar",
    "nicehttp.jar": "https://jitpack.io/com/github/Blatzar/NiceHttp/0.4.18/NiceHttp-0.4.18.jar",
    "cloudstream.jar": "https://github.com/recloudstream/cloudstream/releases/download/pre-release/classes.jar",
}

def fetch(item):
    name, relative = item
    if name == "cloudstream.jar" and os.environ.get("CLOUDSTREAM_JAR"):
        return Path(os.environ["CLOUDSTREAM_JAR"]).resolve()
    dest = CACHE / name
    if not dest.exists():
        url = relative if relative.startswith("https:") else MAVEN + relative
        with urllib.request.urlopen(url, timeout=90) as response, dest.with_suffix(".part").open("wb") as output:
            while block := response.read(1024 * 1024):
                output.write(block)
        dest.with_suffix(".part").replace(dest)
    with zipfile.ZipFile(dest) as jar:
        if jar.testzip():
            raise RuntimeError(f"Corrupt test dependency: {name}")
    return dest

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    jars = dict(zip(ARTIFACTS, pool.map(fetch, ARTIFACTS.items())))
compiler_names = ["compiler.jar", "stdlib.jar", "reflect.jar", "script.jar", "coroutines.jar", "annotations.jar"]
compiler_cp = os.pathsep.join(str(jars[x]) for x in compiler_names)
# JSONObject must be the JVM implementation rather than Android stub methods in the JVM harness.
classpath = os.pathsep.join(str(p) for n,p in jars.items() if n not in ["compiler.jar", "script.jar"])
source = sorted((MODULE / "src/main/kotlin").rglob("*.kt"))
tests = sorted((MODULE / "tests").rglob("*.kt"))

def compile_to(output, sources):
    subprocess.run(["java", "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "11", "-classpath", classpath,
        "-d", str(output), *map(str, sources)], check=True)

compile_to(CACHE / "production.jar", source)
compile_to(CACHE / "tests.jar", source + tests)
for entry in ["com.Moviebox.PlaybackRegressionKt", "com.Moviebox.PlaybackIntegrationKt",
              "com.Moviebox.EpisodeMetadataRegressionKt", "com.Moviebox.PlaybackContextRegressionKt"]:
    subprocess.run(["java", "-Dmoviebox.fixtures="+str(MODULE / "tests/fixtures"), "-cp", str(CACHE / "tests.jar") + os.pathsep + classpath, entry], check=True, timeout=120)
print("PASS production API compilation and fixture suites. Live service/device playback remains unverified.")
