#!/usr/bin/env python3
"""Publish only four replacement Adi packages; retain every unrelated published byte/entry."""
import json
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
MODULES = {"AdiDrakor", "AdiFilmSemi", "AdiXtream", "Adicinemax21"}
folder = Path(sys.argv[1])
before = json.loads(Path(sys.argv[2]).read_text())
generated = json.loads((ROOT / "build/plugins.json").read_text())
updates = {p["internalName"]: p for p in generated if p["internalName"] in MODULES}
assert set(updates) == MODULES, "Missing generated Adi metadata"
assert MODULES <= {p["internalName"] for p in before}, "Unexpected catalog identity change"
for module in sorted(MODULES):
    source = ROOT / module / "build" / (module + ".cs3")
    assert source.is_file(), f"Missing package: {module}"
    shutil.copyfile(source, folder / source.name)
rows = [updates.get(p["internalName"], p) for p in before]
(folder / "plugins.json").write_text(json.dumps(rows, indent=2) + "\n")
print(f"Prepared {len(rows)} entries; replaced only {', '.join(sorted(MODULES))}")
