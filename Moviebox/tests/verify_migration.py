#!/usr/bin/env python3
"""Verify upstream Moviebox bytes and the complete protected Adi file inventory."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = json.loads(Path(__file__).with_name("migration_manifest.json").read_text())
ADI = ["AdiDrakor", "AdiFilmSemi", "AdiXtream", "Adicinemax21"]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    names = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *ADI],
        cwd=ROOT,
    ).decode().strip("\0").split("\0")
    actual = {name: digest(ROOT / name) for name in names}
    assert actual == MANIFEST["adiSha256"], "Protected Adi paths or bytes changed"
    for name, expected in MANIFEST["sourceMovieboxSha256"].items():
        assert digest(ROOT / name) == expected, "Upstream Moviebox file changed: " + name
    expected_production = {name for name in MANIFEST["sourceMovieboxSha256"] if "/src/" in name}
    actual_production = {
        str(path.relative_to(ROOT)) for path in (ROOT / "Moviebox/src").rglob("*") if path.is_file()
    }
    assert actual_production == expected_production, "Missing/obsolete Moviebox production files"
    print("PASS Moviebox source parity:", len(MANIFEST["sourceMovieboxSha256"]), "upstream files")
    print("PASS Adi source hash parity:", len(actual), "unchanged paths and SHA-256 hashes")


if __name__ == "__main__":
    main()
