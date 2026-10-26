#!/usr/bin/env python3
"""Verify the standalone Moviebox source against its upstream baseline."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = json.loads(Path(__file__).with_name("migration_manifest.json").read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    for name, expected in MANIFEST["sourceMovieboxSha256"].items():
        assert digest(ROOT / name) == expected, "Upstream Moviebox file changed: " + name
    expected_production = {name for name in MANIFEST["sourceMovieboxSha256"] if "/src/" in name}
    actual_production = {
        str(path.relative_to(ROOT)) for path in (ROOT / "Moviebox/src").rglob("*") if path.is_file()
    }
    assert actual_production == expected_production, "Missing/obsolete Moviebox production files"
    print("PASS Moviebox source parity:", len(MANIFEST["sourceMovieboxSha256"]), "upstream files")


if __name__ == "__main__":
    main()
