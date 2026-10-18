"""Pin source behavior after formatting, independent of target's standalone Moviebox module."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def verify(root, env):
    tests = root / 'AdiDrakor/tests'
    manifest = json.loads((tests / 'migration_manifest.json').read_text())
    expected = manifest['productionFingerprints']
    actual_paths = {str(p.relative_to(root)) for m in ('AdiDrakor', 'AdiFilmSemi', 'AdiXtream', 'Adicinemax21')
                    for p in (root / m / 'src/main').rglob('*') if p.is_file()}
    source_paths = {p for p in manifest['sourceFiles'] if '/src/main/' in p}
    assert actual_paths == source_paths, ('Missing/new production paths', actual_paths ^ source_paths)
    for path in source_paths:
        if not path.endswith('.kt'):
            assert hashlib.sha256((root / path).read_bytes()).hexdigest() == manifest['sourceFiles'][path], 'Non-Kotlin production drift: ' + path
    for path, digest in manifest['testOnlySnapshot'].items():
        actual = tests / 'upstream/Moviebox' / path
        assert hashlib.sha256(actual.read_bytes()).hexdigest() == digest, 'Changed upstream fixture: ' + path
    with tempfile.TemporaryDirectory(prefix='adi-token-parity-') as temp:
        jar = Path(temp) / 'fingerprint.jar'
        classpath = env['compiler_cp'] + ':' + env['classpath']
        subprocess.run(['java', '-cp', env['compiler_cp'], 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', classpath,
                        '-d', str(jar), str(tests / 'tools/SourceFingerprint.kt')], check=True)
        rows = json.loads(subprocess.check_output(['java', '-cp', str(jar) + ':' + classpath,
                          'SourceFingerprintKt', *expected], cwd=root, text=True))
        assert {r['path'] for r in rows} == set(expected)
        for row in rows:
            original = expected[row['path']]
            assert row['semanticSha256'] == original['semanticSha256'], 'Source behavior drift: ' + row['path']
            assert set(row['imports']) <= set(original['imports']), 'Unapproved import change: ' + row['path']
    print(f"SOURCE_PARITY {len(rows)} PASS: entire production token streams match source {manifest['sourceSha']}; "
          f"{len(manifest['testOnlySnapshot'])} immutable upstream snapshot files; exact production path set", flush=True)
