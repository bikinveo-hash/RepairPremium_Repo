"""Exercise changed-module selection and publication with valid synthetic DEX tables."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

import distribution as release


def dex_fixture(classes):
    count = len(classes)
    strings_offset = 112
    types_offset = strings_offset + count * 4
    classes_offset = types_offset + count * 4
    data = bytearray(classes_offset + count * 32)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<II", data, 36, 112, 0x12345678)
    struct.pack_into("<II", data, 56, count, strings_offset)
    struct.pack_into("<II", data, 64, count, types_offset)
    struct.pack_into("<II", data, 96, count, classes_offset)
    for index, name in enumerate(classes):
        struct.pack_into("<I", data, strings_offset + index * 4, len(data))
        struct.pack_into("<I", data, types_offset + index * 4, index)
        struct.pack_into("<I", data, classes_offset + index * 32, index)
        encoded = name.encode()
        assert len(encoded) < 128
        data.extend(bytes([len(encoded)]) + encoded + b"\0")
    struct.pack_into("<I", data, 32, len(data))
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    return bytes(data)


def artifact(path, name, version, extra=()):
    package = release.ADI.get(name, "com.example")
    plugin = package + "." + name + "Plugin"
    classes = {"L" + plugin.replace(".", "/") + ";", *extra}
    if name in release.ADI:
        classes.update("L" + package.replace(".", "/") + "/" + c + ";" for c in (
            name, name + "MovieboxEngine", name + "MovieboxMapping", name + "MovieboxMedia",
            name + "MovieboxPlayback", "MediaIdentity", "MultiSource", "IdlixSource",
            "Majorplay", "OppaSource", "OppaPlayback", "LocalProxy", "HydraxMedia"))
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("manifest.json", json.dumps({
            "name": name, "version": version, "pluginClassName": plugin}))
        archive.writestr("classes.dex", dex_fixture(sorted(classes)))
    return {"internalName": name, "name": name, "version": version,
            "fileSize": path.stat().st_size, "fileHash": "sha256-" + release.sha256(path),
            "url": f"https://raw.githubusercontent.com/{release.REPOSITORY}/builds/{name}.cs3",
            "repositoryUrl": f"https://github.com/{release.REPOSITORY}"}


class DistributionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source"
        self.folder = Path(self.temp.name) / "published"
        self.root.mkdir()
        self.folder.mkdir()
        self.before = [artifact(self.folder / (name + ".cs3"), name, 26)
                       for name in ("Other", "AdiDrakor")]
        (self.folder / "plugins.json").write_text(json.dumps(self.before))
        (self.folder / "repo.json").write_text(json.dumps({"pluginLists": [
            f"https://raw.githubusercontent.com/{release.REPOSITORY}/builds/plugins.json"]}))
        self.state = {"modules": ["AdiDrakor"], "catalog": copy.deepcopy(self.before),
                      "files": release.inventory(self.folder)}
        self.row = self.output("AdiDrakor", 27)
        self.patcher = patch.object(release, "ROOT", self.root)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def output(self, name, version, extra=()):
        row = artifact(self.root / name / "build" / (name + ".cs3"), name, version, extra)
        (self.root / name / "build.gradle.kts").write_text(f"version = {version}\n")
        self.write_entry(name, row)
        return row

    def write_entry(self, name, row):
        (self.root / name / "build/plugin-entry.json").write_text(json.dumps(row))

    def reject(self, message):
        before = release.inventory(self.folder)
        with self.assertRaisesRegex(AssertionError, message):
            release.prepare(self.folder, self.state)
        self.assertEqual(before, release.inventory(self.folder))

    def test_only_selected_package_and_catalog_change(self):
        release.prepare(self.folder, self.state)
        after = release.inventory(self.folder)
        self.assertEqual({p for p in after if after[p] != self.state["files"][p]},
                         {"AdiDrakor.cs3", "plugins.json"})
        self.assertEqual(release.verify_catalog(self.folder)["Other"], self.before[0])

    def test_empty_selection_does_not_rewrite_any_file(self):
        self.state["modules"] = []
        release.prepare(self.folder, self.state)
        self.assertEqual(release.inventory(self.folder), self.state["files"])

    def test_multiple_selected_packages(self):
        self.output("NewPlugin", 1)
        self.state["modules"].append("NewPlugin")
        release.prepare(self.folder, self.state)
        self.assertEqual(list(release.verify_catalog(self.folder)), ["Other", "AdiDrakor", "NewPlugin"])

    def test_downgrade_rejected_before_copying(self):
        self.output("AdiDrakor", 25)
        self.reject("downgrade")

    def test_cross_platform_jar_is_published_with_its_metadata(self):
        jar = self.root / "AdiDrakor/build/AdiDrakor.jar"
        jar.write_bytes(b"compiled jar fixture")
        self.row.update(jarUrl=f"https://raw.githubusercontent.com/{release.REPOSITORY}/builds/AdiDrakor.jar",
                        jarFileSize=jar.stat().st_size, jarHash="sha256-" + release.sha256(jar))
        self.write_entry("AdiDrakor", self.row)
        release.prepare(self.folder, self.state)
        self.assertEqual((self.folder / jar.name).read_bytes(), jar.read_bytes())

    def test_corrupt_cross_platform_jar_rejected(self):
        jar = self.root / "AdiDrakor/build/AdiDrakor.jar"
        jar.write_bytes(b"invalid")
        self.row.update(jarUrl=f"https://raw.githubusercontent.com/{release.REPOSITORY}/builds/AdiDrakor.jar",
                        jarFileSize=jar.stat().st_size, jarHash="sha256-wrong")
        self.write_entry("AdiDrakor", self.row)
        self.reject("JAR mismatch")

    def test_same_version_changed_bytes_rejected(self):
        self.output("AdiDrakor", 26, {"Lcom/AdiDrakor/Changed;"})
        self.reject("version increase")

    def test_corrupt_package_rejected_before_copying(self):
        (self.root / "AdiDrakor/build/AdiDrakor.cs3").write_bytes(b"corrupt")
        self.reject("size mismatch")

    def test_wrong_hash_rejected(self):
        self.row["fileHash"] = "sha256-wrong"
        self.write_entry("AdiDrakor", self.row)
        self.reject("hash mismatch")

    def test_source_version_mismatch_rejected(self):
        (self.root / "AdiDrakor/build.gradle.kts").write_text("version = 28\n")
        self.reject("differs from source")

    def test_wrong_repository_rejected(self):
        self.row["repositoryUrl"] = "https://github.com/other/repo"
        self.write_entry("AdiDrakor", self.row)
        self.reject("repository identity")

    def test_wrong_url_rejected(self):
        self.row["url"] = self.row["url"].replace("builds", "main")
        self.write_entry("AdiDrakor", self.row)
        self.reject("artifact URL")

    def test_old_nested_classes_rejected(self):
        self.output("AdiDrakor", 27, {"Lcom/AdiDrakor/sources/LocalProxy;"})
        self.reject("Old nested package")

    def test_duplicate_plugin_entry_rejected(self):
        self.output("AdiDrakor", 27, {"Lcom/AdiDrakor/OldPlugin;"})
        self.reject("Duplicate plugin")

    def test_second_invalid_package_cannot_partially_publish_first(self):
        self.output("NewPlugin", 1)
        self.state["modules"].append("NewPlugin")
        (self.root / "NewPlugin/build/NewPlugin.cs3").write_bytes(b"corrupt")
        self.reject("size mismatch")

    def test_external_distribution_change_rejected(self):
        (self.folder / "unexpected.cs3").write_bytes(b"extra")
        self.reject("changed after planning")

    def test_duplicate_catalog_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Duplicate"):
            release.catalog(self.before + self.before[:1])

    def test_dex_checksum_rejected(self):
        data = bytearray(dex_fixture(["Lcom/example/Plugin;"]))
        data[-1] ^= 1
        with self.assertRaisesRegex(AssertionError, "signature"):
            release.dex_classes(data)

    def test_only_changed_module_selected(self):
        self.assertEqual(release.changed_modules({"AdiDrakor", "Mangoporn"},
            ["Mangoporn/src/main/kotlin/Provider.kt", ".github/workflows/newrun.yml"]), ["Mangoporn"])

    def test_shared_dependency_change_selects_all(self):
        self.assertEqual(release.changed_modules({"Other", "AdiDrakor"}, ["build.gradle.kts"]),
                         ["AdiDrakor", "Other"])

    def test_test_and_docs_changes_do_not_rebuild_packages(self):
        self.assertEqual(release.changed_modules({"AdiDrakor"},
            ["AdiDrakor/tests/Fixture.kt", "AdiDrakor/README.md"]), [])

    def test_nested_fixture_sources_do_not_rebuild_runtime_plugins(self):
        self.assertEqual(release.changed_modules({"AdiDrakor"}, [
            "AdiDrakor/tests/upstream/Moviebox/src/main/kotlin/Provider.kt",
            "AdiDrakor/tests/upstream/Moviebox/build.gradle.kts",
            "AdiDrakor/tests/run_regression.py",
        ]), [])

    def test_plan_covers_changes_since_last_publication(self):
        def git(where, *args):
            return subprocess.check_output(["git", *args], cwd=where, text=True).strip()
        for where in (self.root, self.folder):
            git(where, "init", "-q")
            git(where, "config", "user.name", "Release test")
            git(where, "config", "user.email", "test@example.invalid")
        git(self.root, "add", ".")
        git(self.root, "commit", "-qm", "baseline")
        baseline = git(self.root, "rev-parse", "HEAD")
        git(self.folder, "add", ".")
        git(self.folder, "commit", "-qm", "Build " + baseline)
        source = self.root / "AdiDrakor/src/main/kotlin/Provider.kt"
        source.parent.mkdir(parents=True)
        source.write_text("first change")
        git(self.root, "add", ".")
        git(self.root, "commit", "-qm", "cancelled previous build")
        self.output("Other", 27)
        git(self.root, "add", ".")
        git(self.root, "commit", "-qm", "latest change")
        state = release.plan(self.folder, self.root / "plan.json")
        self.assertEqual(state["modules"], ["AdiDrakor", "Other"])
        self.assertEqual(state["base"], baseline)


if __name__ == "__main__":
    unittest.main()
