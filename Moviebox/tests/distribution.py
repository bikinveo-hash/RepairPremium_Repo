#!/usr/bin/env python3
"""Publish standalone Moviebox or one selected plugin, preserving unrelated bytes.

The existing Adi staging/verification scripts remain unchanged. retain-adi supplies
their publication inputs from the release, after compilation has already succeeded.
Only ignored build outputs are written; no Adi source/test/configuration is touched.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
ADI = {"AdiDrakor", "AdiFilmSemi", "AdiXtream", "Adicinemax21"}
REPOSITORY = "bikinveo-hash/RepairPremium_Repo"
MOVIEBOX_CLASSES = {
    "Lcom/Moviebox/" + name + ";" for name in (
        "MovieBoxProvider", "MovieboxPlugin", "MovieboxPlayback",
        "MovieboxPlaybackContext", "MovieboxEpisodes",
    )
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def catalog(rows):
    result = {row["internalName"]: row for row in rows}
    assert len(rows) == len(result), "Duplicate catalog identity"
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
        # DEX strings use modified UTF-8 (including C0 80 for NUL). Class
        # descriptors compared below are ASCII; unrelated string constants can
        # contain modified encodings and must not reject an otherwise valid DEX.
        strings.append(data[start:data.index(b"\0", start)].decode("utf-8", errors="replace"))
    count, offset = struct.unpack_from("<II", data, 64)
    types = [strings[struct.unpack_from("<I", data, offset + index * 4)[0]] for index in range(count)]
    count, offset = struct.unpack_from("<II", data, 96)
    classes = [types[struct.unpack_from("<I", data, offset + index * 32)[0]] for index in range(count)]
    assert len(classes) == len(set(classes)), "Duplicate DEX class"
    return classes


def verify_package(path, row, moviebox=False):
    assert path.stat().st_size == row["fileSize"], "Artifact size mismatch: " + path.name
    assert row["fileHash"] == "sha256-" + sha256(path), "Artifact hash mismatch: " + path.name
    assert row["url"] == f"https://raw.githubusercontent.com/{REPOSITORY}/builds/{row['internalName']}.cs3", "Incorrect artifact URL"
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, "Corrupt ZIP"
        manifest = json.loads(archive.read("manifest.json"))
        assert manifest["name"] == row["internalName"] and manifest["version"] == row["version"], "Manifest mismatch"
        classes = dex_classes(archive.read("classes.dex"))
        if moviebox:
            assert manifest["pluginClassName"] == "com.Moviebox.MovieboxPlugin", "Incorrect Moviebox plugin class"
            assert MOVIEBOX_CLASSES <= set(classes), "Missing Moviebox production classes"
            assert [name for name in classes if name.endswith("Plugin;")] == ["Lcom/Moviebox/MovieboxPlugin;"], "Duplicate plugin entry"
            assert not any(name.startswith(("Lcom/AdiDrakor/", "Lcom/michat88/", "Lcom/adixtream/", "Lcom/Adicinemax21/")) for name in classes), "Adi dependency in standalone Moviebox"
            assert not any("Regression" in name or "Integration" in name or name.startswith("Landroid/") for name in classes), "Test classes packaged"
        elif row["internalName"] == "Mangoporn" and row["version"] >= 7:
            plugin = "com.Mangoporn.MangoPornPlugin"
            entry = "Lcom/Mangoporn/MangoPornPlugin;"
            assert manifest["pluginClassName"] == plugin, "Incorrect MangoPorn plugin class"
            assert {"Lcom/Mangoporn/MangoPorn;", entry} <= set(classes), "Missing MangoPorn production classes"
            assert [name for name in classes if name.endswith("Plugin;")] == [entry], "Duplicate MangoPorn plugin entry"
        assert not any(name.startswith("com/Moviebox/tests/") or name.endswith(("Regression.class", "Integration.class")) for name in archive.namelist()), "Test classes packaged"
        return manifest


def inventory(folder):
    return {
        str(path.relative_to(folder)): sha256(path)
        for path in sorted(folder.rglob("*"))
        if path.is_file() and ".git" not in path.relative_to(folder).parts
    }


def verify_catalog(folder):
    rows = catalog(json.loads((folder / "plugins.json").read_text()))
    for name, row in rows.items():
        verify_package(folder / (name + ".cs3"), row, moviebox=name == "Moviebox" and row["version"] >= 20)
    return rows


def snapshot(folder, state_file):
    rows = verify_catalog(folder)
    assert ADI | {"Moviebox"} <= set(rows), "Missing protected catalog entries"
    state_file.write_text(json.dumps({"catalog": list(rows.values()), "files": inventory(folder)}, indent=2) + "\n")
    print("PASS publication baseline:", len(rows), "plugins")


def preserved(folder, state, allow_moviebox):
    before = catalog(state["catalog"])
    after = verify_catalog(folder)
    assert set(before) == set(after), "Catalog identities changed"
    for name in before:
        if name != "Moviebox" or not allow_moviebox:
            assert before[name] == after[name], "Protected catalog entry changed: " + name
    files = inventory(folder)
    assert files.keys() == state["files"].keys(), "Published file inventory changed"
    allowed = {"plugins.json"} | ({"Moviebox.cs3"} if allow_moviebox else set())
    for path, digest in state["files"].items():
        if path not in allowed:
            assert files[path] == digest, "Protected published file changed: " + path
    return before, after


def retain_adi(folder, state):
    before, _ = preserved(folder, state, allow_moviebox=False)
    generated_file = ROOT / "build/plugins.json"
    generated = json.loads(generated_file.read_text())
    compiled = catalog(generated)
    assert ADI <= set(compiled), "Missing compiled Adi metadata"
    for name in sorted(ADI):
        output = ROOT / name / "build" / (name + ".cs3")
        assert output.is_file(), "Adi compilation output missing: " + name
        verify_package(output, compiled[name])
        # Compilation is a control only. Reuse the released bytes for the unchanged
        # Adi publication stage, regardless of compiler/archive nondeterminism.
        shutil.copyfile(folder / output.name, output)
    generated_file.write_text(json.dumps([
        before[row["internalName"]] if row["internalName"] in ADI else row for row in generated
    ], indent=2) + "\n")
    print("PASS Adi publication inputs retained from released artifacts; source untouched")


def prepare(folder, state):
    before, _ = preserved(folder, state, allow_moviebox=False)
    generated = catalog(json.loads((ROOT / "build/plugins.json").read_text()))
    row = generated["Moviebox"]
    source = ROOT / "Moviebox/build/Moviebox.cs3"
    verify_package(source, row, moviebox=True)
    version = int(re.search(r"^version\s*=\s*(\d+)", (ROOT / "Moviebox/build.gradle.kts").read_text(), re.M)[1])
    assert row["version"] == version, "Generated version differs from source"
    assert row["version"] >= before["Moviebox"]["version"], "Moviebox version downgrade"
    if row["version"] == before["Moviebox"]["version"]:
        assert row["fileHash"] == before["Moviebox"]["fileHash"], "Changed Moviebox needs a version increase"
    # All checks happen before changing the disposable distribution checkout.
    temporary = folder / "Moviebox.cs3.pending"
    shutil.copyfile(source, temporary)
    temporary.replace(folder / "Moviebox.cs3")
    rows = [row if old["internalName"] == "Moviebox" else old for old in state["catalog"]]
    temporary = folder / "plugins.json.pending"
    temporary.write_text(json.dumps(rows, indent=2) + "\n")
    temporary.replace(folder / "plugins.json")
    verify(folder, state)


def verify(folder, state):
    before, after = preserved(folder, state, allow_moviebox=True)
    row = after["Moviebox"]
    assert row["version"] >= before["Moviebox"]["version"], "Moviebox version downgrade"
    if row["version"] == before["Moviebox"]["version"]:
        assert row["fileHash"] == before["Moviebox"]["fileHash"], "Changed Moviebox needs a version increase"
    version = int(re.search(r"^version\s*=\s*(\d+)", (ROOT / "Moviebox/build.gradle.kts").read_text(), re.M)[1])
    assert row["version"] == version, "Published version differs from source"
    verify_package(folder / "Moviebox.cs3", row, moviebox=True)
    generated = catalog(json.loads((ROOT / "build/plugins.json").read_text()))["Moviebox"]
    assert row == generated, "Moviebox publication differs from generated metadata"
    assert sha256(folder / "Moviebox.cs3") == sha256(ROOT / "Moviebox/build/Moviebox.cs3"), "Published Moviebox differs from build"
    print("PASS final catalog:", len(after), "plugins; Moviebox version", row["version"])
    print("PASS Adi artifact hash parity; all non-Moviebox entries and published bytes unchanged")


def publish_plugin(folder, module):
    """Run after Moviebox verification; allow exactly this module's package/entry."""
    assert re.fullmatch(r"[A-Za-z0-9_-]+", module), "Invalid plugin identity"
    before = verify_catalog(folder)
    files_before = inventory(folder)
    generated = catalog(json.loads((ROOT / "build/plugins.json").read_text()))
    row = generated[module]
    old = before[module]
    source = ROOT / module / "build" / (module + ".cs3")
    verify_package(source, row)
    version = int(re.search(r"^version\s*=\s*(\d+)", (ROOT / module / "build.gradle.kts").read_text(), re.M)[1])
    assert row["version"] == version, "Generated version differs from source"
    assert version >= old["version"], "Plugin version downgrade"
    if version == old["version"]:
        assert row["fileHash"] == old["fileHash"], "Changed plugin needs a version increase"
    # All inputs have been validated before changing the distribution checkout.
    shutil.copyfile(source, folder / source.name)
    rows = [row if item["internalName"] == module else item for item in before.values()]
    (folder / "plugins.json").write_text(json.dumps(rows, indent=2) + "\n")
    after = verify_catalog(folder)
    files_after = inventory(folder)
    assert list(before) == list(after), "Catalog identities/order changed"
    assert files_before.keys() == files_after.keys(), "Published file inventory changed"
    for name in before:
        if name != module:
            assert before[name] == after[name], "Unrelated catalog entry changed: " + name
    for path, value in files_before.items():
        if path not in {"plugins.json", source.name}:
            assert files_after[path] == value, "Unrelated published file changed: " + path
    assert after[module] == row and sha256(folder / source.name) == sha256(source), "Published plugin differs from build"
    print("PASS selected publication:", module, "version", version, "; unrelated metadata and bytes preserved")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["snapshot", "retain-adi", "prepare", "verify", "publish-plugin"])
    parser.add_argument("distribution", type=Path)
    parser.add_argument("state", type=Path, nargs="?")
    parser.add_argument("--module")
    args = parser.parse_args()
    if args.command == "publish-plugin":
        if not args.module:
            parser.error("publish-plugin requires --module")
        publish_plugin(args.distribution, args.module)
    elif args.state is None:
        parser.error(args.command + " requires a state file")
    elif args.command == "snapshot":
        snapshot(args.distribution, args.state)
    else:
        state = json.loads(args.state.read_text())
        {"retain-adi": retain_adi, "prepare": prepare, "verify": verify}[args.command](args.distribution, state)


if __name__ == "__main__":
    main()
