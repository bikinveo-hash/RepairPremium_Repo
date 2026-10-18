#!/usr/bin/env python3
"""Verify every catalog artifact and preserve non-Adi metadata and bytes exactly."""
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

folder = Path(sys.argv[1])
before = json.loads(Path(sys.argv[2]).read_text()) if len(sys.argv) > 2 else []
after = json.loads((folder / 'plugins.json').read_text())
expected = {'AdiDrakor': 'com.AdiDrakor', 'AdiFilmSemi': 'com.michat88',
            'AdiXtream': 'com.adixtream', 'Adicinemax21': 'com.Adicinemax21'}
old = {p['internalName']: p for p in before}
migration = json.loads(Path(__file__).with_name('migration_manifest.json').read_text())
assert len(after) == len({p['internalName'] for p in after}), 'Duplicate catalog names'
assert set(old) == {p['internalName'] for p in after} if before else True, 'Catalog identities changed'


def dex_classes(data):
    """Read class_defs, not string references: assert one real plugin entry and no legacy definitions."""
    strings_count, strings_offset = struct.unpack_from('<II', data, 56)
    strings = []
    for i in range(strings_count):
        offset = struct.unpack_from('<I', data, strings_offset + i * 4)[0]
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        end = data.index(b'\0', offset)
        strings.append(data[offset:end].decode('utf-8', errors='replace'))
    count, offset = struct.unpack_from('<II', data, 64)
    types = [strings[struct.unpack_from('<I', data, offset + i * 4)[0]] for i in range(count)]
    count, offset = struct.unpack_from('<II', data, 96)
    return [types[struct.unpack_from('<I', data, offset + i * 32)[0]] for i in range(count)]


for item in after:
    name = item['internalName']
    file = folder / (name + '.cs3')
    assert file.stat().st_size == item['fileSize'], name + ' size mismatch'
    assert item['fileHash'] == 'sha256-' + hashlib.sha256(file.read_bytes()).hexdigest(), name + ' hash mismatch'
    with zipfile.ZipFile(file) as archive:
        assert archive.testzip() is None, name
        manifest = json.loads(archive.read('manifest.json'))
        assert manifest['name'] == name and manifest['version'] == item['version'], name
        dex = archive.read('classes.dex')
        assert dex.startswith(b'dex\n'), name
        if name in expected:
            package = expected[name]
            plugin = package + '.' + name + 'Plugin'
            assert manifest['pluginClassName'] == plugin, (name, manifest)
            assert item['version'] > migration['publishedBeforeVersions'][name], 'Migration must increase version: ' + name
            assert item['version'] >= old[name]['version'], 'Published version downgrade: ' + name
            if item['version'] == old[name]['version']:
                assert item['fileHash'] == old[name]['fileHash'], 'Changed package needs a version increase: ' + name
            assert item['url'] == f'https://raw.githubusercontent.com/bikinveo-hash/RepairPremium_Repo/builds/{name}.cs3'
            classes = dex_classes(dex)
            assert len(classes) == len(set(classes)), 'Duplicate DEX class'
            entry = 'L' + plugin.replace('.', '/') + ';'
            assert entry in classes
            assert [c for c in classes if c.endswith('Plugin;')] == [entry], 'Duplicate plugin entry'
            for adapter in ['MediaIdentity', 'MultiSource', 'IdlixSource', 'Majorplay', 'OppaSource', 'OppaPlayback', 'LocalProxy', 'HydraxMedia']:
                assert 'L' + package.replace('.', '/') + '/sources/' + adapter + ';' in classes, (name, adapter)
            forbidden = ['PlaybackFixedProvider', 'MovieBoxV2Shared', 'VidSrc', 'SubtitleCompat', 'Extractor;', 'Parser;', 'Idlix;']
            assert not any(term in c for c in classes for term in forbidden), 'Legacy class definition: ' + name
            assert not any(c.startswith(('Lcom/Moviebox/', 'Lcom/IdlixProvider/', 'Lcom/OppaDrama/')) for c in classes), 'Cross-plugin/test snapshot class'
            print('PASS artifact', name, 'version', item['version'], 'single entry', plugin)
        else:
            assert item == old[name], 'Unrelated plugin metadata changed: ' + name
print('PASS distribution:', len(after), 'plugins; non-Adi metadata and binary hashes preserved')
