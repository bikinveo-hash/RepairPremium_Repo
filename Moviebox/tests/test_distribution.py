"""Deterministic release guards; synthetic DEX tables are not playback fixtures."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

import distribution as release


def dex_fixture(classes):
    """Minimal class/string tables for the release inspector, with valid checksums."""
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


def artifact(path, name, version, classes=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("manifest.json", json.dumps({"name": name, "version": version,
            "pluginClassName": (
                "com.Moviebox.MovieboxPlugin" if name == "Moviebox" else
                "com.Mangoporn.MangoPornPlugin" if name == "Mangoporn" else
                "com.example." + name + "Plugin"
            )}))
        archive.writestr("classes.dex", dex_fixture(sorted(classes or release.MOVIEBOX_CLASSES)))
    return {"internalName": name, "name": name, "version": version,
        "fileSize": path.stat().st_size, "fileHash": "sha256-" + release.sha256(path),
        "url": f"https://raw.githubusercontent.com/{release.REPOSITORY}/builds/{name}.cs3"}


class DistributionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.folder = self.root / "published"
        self.folder.mkdir()
        self.before = [artifact(self.folder / (name + ".cs3"), name, 12)
            for name in sorted(release.ADI | {"Moviebox", "Other"})]
        (self.folder / "plugins.json").write_text(json.dumps(self.before))
        (self.folder / "repo.json").write_text('{"unchanged":true}')
        self.state = {"catalog": copy.deepcopy(self.before), "files": release.inventory(self.folder)}
        self.update = artifact(self.root / "Moviebox/build/Moviebox.cs3", "Moviebox", 20)
        (self.root / "Moviebox/build.gradle.kts").write_text("version = 20\n")
        (self.root / "build").mkdir()
        self.generated = [self.update if row["internalName"] == "Moviebox" else row for row in self.before]
        (self.root / "build/plugins.json").write_text(json.dumps(self.generated))
        self.patcher = patch.object(release, "ROOT", self.root)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def test_only_moviebox_bytes_and_metadata_change(self):
        release.prepare(self.folder, self.state)
        after = release.inventory(self.folder)
        self.assertEqual({path for path in after if after[path] != self.state["files"][path]}, {"Moviebox.cs3", "plugins.json"})
        release.verify(self.folder, self.state)

    def test_non_moviebox_metadata_change_rejected(self):
        self.before[0]["name"] = "changed"
        (self.folder / "plugins.json").write_text(json.dumps(self.before))
        with self.assertRaisesRegex(AssertionError, "Protected catalog"):
            release.prepare(self.folder, self.state)

    def test_non_moviebox_bytes_change_rejected(self):
        (self.folder / "repo.json").write_text("changed")
        with self.assertRaisesRegex(AssertionError, "Protected published"):
            release.prepare(self.folder, self.state)

    def test_missing_plugin_rejected(self):
        (self.folder / "plugins.json").write_text(json.dumps(self.before[:-1]))
        with self.assertRaisesRegex(AssertionError, "Catalog identities"):
            release.prepare(self.folder, self.state)

    def test_duplicate_plugin_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Duplicate catalog"):
            release.catalog(self.before + self.before[:1])

    def test_extra_published_file_rejected(self):
        (self.folder / "unexpected.cs3").write_bytes(b"extra")
        with self.assertRaisesRegex(AssertionError, "inventory"):
            release.prepare(self.folder, self.state)

    def test_corrupt_artifact_rejected_before_staging(self):
        (self.root / "Moviebox/build/Moviebox.cs3").write_bytes(b"corrupt")
        with self.assertRaises(AssertionError):
            release.prepare(self.folder, self.state)
        self.assertEqual(release.inventory(self.folder), self.state["files"])

    def test_missing_new_class_rejected(self):
        row = artifact(self.root / "missing.cs3", "Moviebox", 20,
            release.MOVIEBOX_CLASSES - {"Lcom/Moviebox/MovieboxPlaybackContext;"})
        with self.assertRaisesRegex(AssertionError, "Missing Moviebox"):
            release.verify_package(self.root / "missing.cs3", row, moviebox=True)

    def test_second_plugin_class_rejected(self):
        row = artifact(self.root / "duplicate.cs3", "Moviebox", 20,
            release.MOVIEBOX_CLASSES | {"Lcom/legacy/OtherPlugin;"})
        with self.assertRaisesRegex(AssertionError, "Duplicate plugin"):
            release.verify_package(self.root / "duplicate.cs3", row, moviebox=True)

    def test_adi_dependency_rejected(self):
        row = artifact(self.root / "cross.cs3", "Moviebox", 20,
            release.MOVIEBOX_CLASSES | {"Lcom/AdiDrakor/Engine;"})
        with self.assertRaisesRegex(AssertionError, "Adi dependency"):
            release.verify_package(self.root / "cross.cs3", row, moviebox=True)

    def test_dex_checksum_rejected(self):
        data = bytearray(dex_fixture(sorted(release.MOVIEBOX_CLASSES)))
        data[-1] ^= 1
        with self.assertRaisesRegex(AssertionError, "signature"):
            release.dex_classes(data)

    def test_version_downgrade_rejected(self):
        self.state["catalog"] = [dict(row, version=21) if row["internalName"] == "Moviebox" else row for row in self.before]
        with patch.object(release, "preserved", return_value=(release.catalog(self.state["catalog"]), {})):
            with self.assertRaisesRegex(AssertionError, "downgrade"):
                release.prepare(self.folder, self.state)

    def test_same_version_different_bytes_rejected(self):
        self.state["catalog"] = [dict(row, version=20) if row["internalName"] == "Moviebox" else row for row in self.before]
        with patch.object(release, "preserved", return_value=(release.catalog(self.state["catalog"]), {})):
            with self.assertRaisesRegex(AssertionError, "version increase"):
                release.prepare(self.folder, self.state)

    def mango_update(self, version=7):
        classes = {"Lcom/Mangoporn/MangoPorn;", "Lcom/Mangoporn/MangoPornPlugin;"}
        old = artifact(self.folder / "Mangoporn.cs3", "Mangoporn", 6, classes)
        rows = json.loads((self.folder / "plugins.json").read_text()) + [old]
        (self.folder / "plugins.json").write_text(json.dumps(rows))
        output = self.root / "Mangoporn/build/Mangoporn.cs3"
        row = artifact(output, "Mangoporn", version, classes)
        (self.root / "Mangoporn/build.gradle.kts").write_text(f"version = {version}\n")
        self.generated.append(row)
        (self.root / "build/plugins.json").write_text(json.dumps(self.generated))
        return row

    def test_selected_publication_preserves_already_staged_moviebox(self):
        release.prepare(self.folder, self.state)
        row = self.mango_update()
        before = release.inventory(self.folder)
        old_rows = release.verify_catalog(self.folder)
        release.publish_plugin(self.folder, "Mangoporn")
        after = release.inventory(self.folder)
        self.assertEqual({p for p in before if before[p] != after[p]}, {"Mangoporn.cs3", "plugins.json"})
        rows = release.verify_catalog(self.folder)
        self.assertEqual(rows["Mangoporn"], row)
        self.assertEqual(rows["Moviebox"], self.update)
        self.assertEqual(list(rows), list(old_rows))

    def test_selected_publication_is_idempotent(self):
        self.mango_update()
        release.publish_plugin(self.folder, "Mangoporn")
        before = release.inventory(self.folder)
        release.publish_plugin(self.folder, "Mangoporn")
        self.assertEqual(before, release.inventory(self.folder))

    def test_selected_downgrade_rejected_before_staging(self):
        self.mango_update(version=5)
        before = release.inventory(self.folder)
        with self.assertRaisesRegex(AssertionError, "downgrade"):
            release.publish_plugin(self.folder, "Mangoporn")
        self.assertEqual(before, release.inventory(self.folder))

    def test_selected_same_version_changed_bytes_rejected(self):
        self.mango_update()
        self.generated[-1]["version"] = 6
        with patch.object(release, "verify_package"):
            (self.root / "Mangoporn/build.gradle.kts").write_text("version = 6\n")
            (self.root / "build/plugins.json").write_text(json.dumps(self.generated))
            before = release.inventory(self.folder)
            with self.assertRaisesRegex(AssertionError, "version increase"):
                release.publish_plugin(self.folder, "Mangoporn")
            self.assertEqual(before, release.inventory(self.folder))

    def test_selected_corrupt_package_rejected_before_staging(self):
        self.mango_update()
        (self.root / "Mangoporn/build/Mangoporn.cs3").write_bytes(b"bad")
        before = release.inventory(self.folder)
        with self.assertRaisesRegex(AssertionError, "size mismatch"):
            release.publish_plugin(self.folder, "Mangoporn")
        self.assertEqual(before, release.inventory(self.folder))

    def test_selected_wrong_source_version_rejected(self):
        self.mango_update()
        (self.root / "Mangoporn/build.gradle.kts").write_text("version = 8\n")
        with self.assertRaisesRegex(AssertionError, "differs from source"):
            release.publish_plugin(self.folder, "Mangoporn")

    def test_mango_missing_entry_class_rejected(self):
        row = artifact(self.root / "missing-mango.cs3", "Mangoporn", 7, {"Lcom/Mangoporn/MangoPorn;"})
        with self.assertRaisesRegex(AssertionError, "Missing MangoPorn"):
            release.verify_package(self.root / "missing-mango.cs3", row)

    def test_mango_duplicate_entry_class_rejected(self):
        row = artifact(self.root / "duplicate-mango.cs3", "Mangoporn", 7, {
            "Lcom/Mangoporn/MangoPorn;", "Lcom/Mangoporn/MangoPornPlugin;", "Lcom/old/OtherPlugin;"})
        with self.assertRaisesRegex(AssertionError, "Duplicate MangoPorn"):
            release.verify_package(self.root / "duplicate-mango.cs3", row)

    def test_retain_adi_uses_released_bytes_without_source_changes(self):
        compiled = copy.deepcopy(self.generated)
        for name in release.ADI:
            output = self.root / name / "build" / (name + ".cs3")
            output.parent.mkdir(parents=True)
            row = artifact(output, name, 13)
            compiled = [row if item["internalName"] == name else item for item in compiled]
            (self.root / name / "source.kt").write_text("unchanged source")
        (self.root / "build/plugins.json").write_text(json.dumps(compiled))
        release.retain_adi(self.folder, self.state)
        for name in release.ADI:
            self.assertEqual((self.root / name / "build" / (name + ".cs3")).read_bytes(), (self.folder / (name + ".cs3")).read_bytes())
            self.assertEqual((self.root / name / "source.kt").read_text(), "unchanged source")


if __name__ == "__main__":
    unittest.main()
