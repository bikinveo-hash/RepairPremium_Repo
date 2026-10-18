# Standalone Moviebox migration

Source: `trinityzanetamanu-code/Premium_RepoX`, commit
`15338233a29ffebd3b734411529f57e5c780bc4f`.
Destination baseline: `54568330914c3d683cf3c20ab0b5e6ae43f90dd1`.

All 36 tracked upstream Moviebox files, including production version 20 and the
complete test suite, are copied byte for byte. No playback algorithm, audio policy,
catalog identity or plugin registration is changed. Three production helpers are
added to the old destination's two Kotlin files; no separate legacy files existed.
The migration adds only this document, the hash manifest, integrity verifier,
distribution guard and its tests, plus the publication workflow changes.

`migration_manifest.json` records every upstream file hash and all 126 protected
tracked Adi paths/hashes. Run `python3 Moviebox/tests/verify_migration.py` to check
both inventories. The Adi engine snapshots, fixtures, fingerprints and scripts are
untouched and remain independent of the standalone Moviebox module.

## Release isolation

1. Snapshot and validate the existing builds catalog and all published file hashes.
2. Run unchanged Adi regressions, standalone Moviebox regressions and diagnostics.
3. Compile Adi as a control and build standalone Moviebox; generate plugin metadata.
4. `distribution.py retain-adi` supplies the existing Adi staging scripts with the
   already released Adi bytes and metadata, writing only ignored build outputs.
   Recompiled Adi packages are never published. The original Adi prepare/verify
   scripts then run without modification.
5. `distribution.py prepare` validates the new Moviebox archive, manifest, DEX
   class definitions/checksums, identity, version, size, hash and repository URL.
   Only Moviebox.cs3 and its catalog row are replaced.
6. The final guard requires the identical catalog membership and every other
   released entry/file/hash, including all four Adi artifacts and repo.json.
   Publication uses the existing builds branch history and a non-force push.

Run release guard tests with:
`python3 -m unittest discover -s Moviebox/tests -p 'test_distribution.py'`.
Run diagnostics with:
`python3 -m unittest discover -s Moviebox/tests/diagnostics -p 'test_*.py'`.
The upstream `run_regression.py` checks actual production against the CloudStream
SDK and deterministic JVM/local HTTP fixtures. These checks do not establish live
CDN playback or playback on an Android device.
