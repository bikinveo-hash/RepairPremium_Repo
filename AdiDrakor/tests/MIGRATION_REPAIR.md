# RepairPremium_Repo: clean replacement of four Adi modules

Source: `trinityzanetamanu-code/Premium_RepoX`, commit
`15338233a29ffebd3b734411529f57e5c780bc4f`.
Target baseline: `bikinveo-hash/RepairPremium_Repo`, commit
`256791bfdc06009750bd7b5f2a3d87379aa7e33a`.

The 51 tracked files previously under the four Adi directories were replaced by
the source's 100 tracked files. Thirty-one obsolete paths disappear rather than
surviving an overwrite. The new production path sets match the source exactly.
No standalone Moviebox, IdlixProvider, OppaDrama or other provider source is changed.

## Formatting without playback changes

Ktfmt 0.54, Kotlin language style, formats 64 production Kotlin files and four
module Gradle files. Sixteen unused imports are removed. No playback algorithm,
endpoint, media header, timeout, audio rule, subtitle mapping, episode selection,
proxy transport or lifecycle behavior is intentionally changed.

`migration_manifest.json` records the source and target-before SHA-256 file
manifests, deleted legacy paths, final hashes, original imports and executable
source fingerprints. `tools/SourceFingerprint.kt` uses the actual Kotlin lexer:
it preserves token types, order, boundaries and complete string contents while
ignoring comments/whitespace and optional separators. Formatting and import
ordering/removal therefore cannot conceal a changed request, branch or literal.
Compilation and the unchanged functional fixtures also verify Kotlin newline and
semicolon behavior. The source fingerprint comparison covers every production
Kotlin file, replacing the former formatting-sensitive snippet equality with
whole-file parity. Production path equality rejects missing or leftover classes.

## Independent upstream test dependency

The target's standalone Moviebox intentionally remains its existing implementation.
The 21 files under `upstream/Moviebox/` are an immutable, test-only snapshot of the
source Moviebox production/regression dependency. The source manifest pins every
snapshot file hash. No Gradle sourceSet includes it; no build.gradle is added there;
artifact checks forbid its package in any Adi DEX.

`run_migration.py` executes this baseline's unchanged regressions, then independently
compiles and tests the four migrated real production modules. Existing media,
identity, bilingual, callback, header, timeout, cancellation and proxy assertions
remain active. The four-plugin isolation test also verifies concurrent distinct
loopback ports and actual plugin `beforeUnload()` cleanup.

Run:

```sh
python3 AdiDrakor/tests/run_migration.py
./gradlew :AdiDrakor:assembleRelease :AdiFilmSemi:assembleRelease :AdiXtream:assembleRelease :Adicinemax21:assembleRelease
./gradlew makePluginsJson
```

The historical live probes are optional; fixture success does not prove Android
player success or current server availability. Prior environment observations of
HTTP 403 are not treated as evidence that any title currently plays.

## Safe distribution

The workflow checks out the existing builds history in detached mode and refuses
to recreate a missing branch. It runs regressions, the four explicit release builds
and makePluginsJson. `prepare_distribution.py` replaces only four Adi packages and
their metadata rows. Every unrelated package and metadata entry is retained.
`verify_artifacts.py` verifies every catalog ZIP/manifest/hash, increasing Adi
versions (an identical rerun is allowed), adapter DEX definitions and absence of legacy, duplicate plugin entries
or cross-plugin test/source classes. Publication creates a normal descendant
commit and uses a non-force push. Repository identity is retained.
