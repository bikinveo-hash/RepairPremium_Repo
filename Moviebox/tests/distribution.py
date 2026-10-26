#!/usr/bin/env python3
"""Build changed plugins and publish matching packages without replacing other releases."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "bikinveo-hash/RepairPremium_Repo"
ADI = {"AdiDrakor": "com.AdiDrakor", "AdiFilmSemi": "com.michat88",
       "AdiXtream": "com.adixtream", "Adicinemax21": "com.Adicinemax21"}
MOVIEBOX_CLASSES = {"Lcom/Moviebox/" + name + ";" for name in (
    "MovieBoxProvider", "MovieboxPlugin", "MovieboxPlayback",
    "MovieboxPlaybackContext", "MovieboxEpisodes",
)}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def catalog(rows):
    result = {row["internalName"]: row for row in rows}
    assert len(result) == len(rows), "Duplicate catalog identity"
    assert all(re.fullmatch(r"[A-Za-z0-9_-]+", name) for name in result), "Invalid catalog identity"
    return result


def dex_classes(data):
    assert len(data) >= 112 and data[:4] == b"dex\n" and data[7] == 0, "Invalid DEX header"
    assert struct.unpack_from("<I", data, 32)[0] == len(data), "DEX length mismatch"
    assert struct.unpack_from("<I", data, 40)[0] == 0x12345678, "Invalid DEX byte order"
    assert data[12:32] == hashlib.sha1(data[32:]).digest(), "Invalid DEX signature"
    assert struct.unpack_from("<I", data, 8)[0] == zlib.adler32(data[12:]) & 0xFFFFFFFF, "Invalid DEX checksum"
    count, offset = struct.unpack_from("<II", data, 56)
    strings = []
    for index in range(count):
        start = struct.unpack_from("<I", data, offset + index * 4)[0]
        while data[start] & 0x80:
            start += 1
        start += 1
        strings.append(data[start:data.index(b"\0", start)].decode("utf-8", errors="replace"))
    count, offset = struct.unpack_from("<II", data, 64)
    types = [strings[struct.unpack_from("<I", data, offset + index * 4)[0]] for index in range(count)]
    count, offset = struct.unpack_from("<II", data, 96)
    classes = [types[struct.unpack_from("<I", data, offset + index * 32)[0]] for index in range(count)]
    assert len(classes) == len(set(classes)), "Duplicate DEX class"
    return classes


def verify_package(path, row, selected=False):
    name = row["internalName"]
    assert path.stat().st_size == row["fileSize"], "Artifact size mismatch: " + name
    assert row["fileHash"] == "sha256-" + sha256(path), "Artifact hash mismatch: " + name
    assert row["url"] == f"https://raw.githubusercontent.com/{REPOSITORY}/builds/{name}.cs3", "Incorrect artifact URL"
    if selected:
        assert row.get("repositoryUrl") == f"https://github.com/{REPOSITORY}", "Incorrect repository identity: " + name
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, "Corrupt ZIP: " + name
        manifest = json.loads(archive.read("manifest.json"))
        assert manifest["name"] == name and manifest["version"] == row["version"], "Manifest mismatch: " + name
        classes = set(dex_classes(archive.read("classes.dex")))
        plugin = manifest["pluginClassName"]
        entry = "L" + plugin.replace(".", "/") + ";"
        assert entry in classes, "Missing plugin entry: " + name
        expected = {m: pkg + "." + m + "Plugin" for m, pkg in ADI.items()}
        expected.update(Moviebox="com.Moviebox.MovieboxPlugin", Mangoporn="com.Mangoporn.MangoPornPlugin")
        if name in expected:
            assert plugin == expected[name], "Incorrect plugin class: " + name
            assert {c for c in classes if c.endswith("Plugin;")} == {entry}, "Duplicate plugin entry: " + name
        if name == "Moviebox" and row["version"] >= 20:
            assert MOVIEBOX_CLASSES <= classes, "Missing Moviebox production classes"
        if selected and name in ADI:
            prefix = "L" + ADI[name].replace(".", "/") + "/"
            required = {"MediaIdentity", "MultiSource", "IdlixSource", "Majorplay", "OppaSource",
                        "OppaPlayback", "LocalProxy", "HydraxMedia", name,
                        name + "MovieboxEngine", name + "MovieboxMapping",
                        name + "MovieboxMedia", name + "MovieboxPlayback"}
            assert {prefix + c + ";" for c in required} <= classes, "Missing Adi production classes: " + name
            assert not any(c.startswith(prefix + "sources/") for c in classes), "Old nested package: " + name
        if name == "Mangoporn" and row["version"] >= 7:
            assert "Lcom/Mangoporn/MangoPorn;" in classes, "Missing MangoPorn provider"
        assert not any("Regression" in c or "Integration" in c for c in classes), "Test classes packaged: " + name
        return manifest


def inventory(folder):
    return {str(p.relative_to(folder)): sha256(p) for p in sorted(folder.rglob("*"))
            if p.is_file() and ".git" not in p.relative_to(folder).parts}


def verify_catalog(folder):
    rows = catalog(json.loads((folder / "plugins.json").read_text()))
    identity = json.loads((folder / "repo.json").read_text())
    assert identity["pluginLists"] == [
        f"https://raw.githubusercontent.com/{REPOSITORY}/builds/plugins.json"
    ], "Incorrect repository identity"
    for name, row in rows.items():
        verify_package(folder / (name + ".cs3"), row)
        if row.get("jarUrl"):
            jar = folder / (name + ".jar")
            assert row["jarUrl"] == f"https://raw.githubusercontent.com/{REPOSITORY}/builds/{name}.jar"
            assert jar.stat().st_size == row["jarFileSize"] and row["jarHash"] == "sha256-" + sha256(jar), "JAR mismatch: " + name
    return rows


def changed_modules(modules, paths):
    shared = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}
    if any(p in shared or p.startswith("gradle/") for p in paths):
        return sorted(modules)
    selected = set()
    for path in paths:
        module, _, relative = path.partition("/")
        if module in modules and (relative.startswith("src/main/") or relative == "build.gradle.kts"):
            selected.add(module)
    return sorted(selected)


def plan(folder, state_file, rebuild_all=False):
    before = verify_catalog(folder)
    modules = {p.parent.name for p in ROOT.glob("*/build.gradle.kts")}
    assert all(re.fullmatch(r"[A-Za-z0-9_-]+", m) for m in modules), "Invalid module name"
    message = subprocess.check_output(["git", "log", "-1", "--format=%B"], cwd=folder, text=True)
    match = re.search(r"^Build ([0-9a-f]{40})$", message, re.M)
    base = match[1] if match else None
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if not rebuild_all and base:
        subprocess.run(["git", "cat-file", "-e", base + "^{commit}"], cwd=ROOT, check=True)
        paths = subprocess.check_output(
            ["git", "diff", "--name-only", "--no-renames", "-z", base, source, "--"],
            cwd=ROOT,
        ).decode().strip("\0").split("\0")
        removed = {p.split("/")[0] for p in paths if p.endswith("/build.gradle.kts")} & (set(before) - modules)
        assert not removed, "Published module removed; retain its source or remove its release explicitly"
        selected = changed_modules(modules, paths)
    else:
        selected = sorted(modules)
    state = {"source": source, "base": base, "modules": selected,
             "catalog": list(before.values()), "files": inventory(folder)}
    state_file.write_text(json.dumps(state, indent=2) + "\n")
    print("Selected plugins:", ", ".join(selected) or "none")
    return state


def build(folder, state_file, rebuild_all=False):
    state = plan(folder, state_file, rebuild_all)
    if state["modules"]:
        (ROOT / "local.properties").touch(exist_ok=True)
        subprocess.run(["bash", "./gradlew", *[
            ":" + module + ":writeCacheEntry" for module in state["modules"]
        ]], cwd=ROOT, check=True)


def prepare(folder, state):
    assert inventory(folder) == state["files"], "Distribution changed after planning"
    before = catalog(state["catalog"])
    updates = {}
    artifacts = {}
    for module in state["modules"]:
        output = ROOT / module / "build"
        row = json.loads((output / "plugin-entry.json").read_text())
        assert row["internalName"] == module, "Generated plugin identity mismatch"
        verify_package(output / (module + ".cs3"), row, selected=True)
        version = int(re.search(r"^version\s*=\s*(\d+)", (ROOT / module / "build.gradle.kts").read_text(), re.M)[1])
        assert row["version"] == version, "Generated version differs from source: " + module
        if module in before:
            assert version >= before[module]["version"], "Version downgrade: " + module
            if version == before[module]["version"]:
                assert (row["fileHash"], row.get("jarHash")) == (
                    before[module]["fileHash"], before[module].get("jarHash")
                ), "Changed package needs a version increase: " + module
        artifacts[module + ".cs3"] = output / (module + ".cs3")
        if row.get("jarUrl"):
            jar = output / (module + ".jar")
            assert row["jarUrl"] == f"https://raw.githubusercontent.com/{REPOSITORY}/builds/{module}.jar"
            assert jar.stat().st_size == row["jarFileSize"] and row["jarHash"] == "sha256-" + sha256(jar), "JAR mismatch: " + module
            artifacts[jar.name] = jar
        updates[module] = row
    # Every selected input is checked before the distribution checkout is changed.
    for name, source in artifacts.items():
        shutil.copyfile(source, folder / name)
    rows = [updates.get(name, row) for name, row in before.items()]
    rows.extend(row for name, row in updates.items() if name not in before)
    if updates:
        (folder / "plugins.json").write_text(json.dumps(rows, indent=2) + "\n")
    after = verify_catalog(folder)
    files = inventory(folder)
    allowed = {"plugins.json", *artifacts}
    assert set(files) == set(state["files"]) | set(artifacts), "Unexpected published file inventory"
    for name, row in before.items():
        if name not in updates:
            assert after[name] == row, "Unrelated catalog entry changed: " + name
    for path, digest in state["files"].items():
        if path not in allowed:
            assert files[path] == digest, "Unrelated published file changed: " + path
    for name, row in updates.items():
        assert after[name] == row, "Published catalog differs from build: " + name
    for name, source in artifacts.items():
        assert files[name] == sha256(source), "Published artifact differs from build: " + name
    print("PASS publication:", len(updates), "updated plugins;", len(after), "catalog entries; unrelated releases preserved")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["build", "prepare"])
    parser.add_argument("distribution", type=Path)
    parser.add_argument("state", type=Path)
    args = parser.parse_args()
    if args.command == "build":
        build(args.distribution, args.state, os.environ.get("REBUILD_ALL", "false").lower() == "true")
    else:
        prepare(args.distribution, json.loads(args.state.read_text()))


if __name__ == "__main__":
    main()
