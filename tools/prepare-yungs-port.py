"""Reproduce the initial Paper source/resource import from the pinned, unmodified snapshots.

Run only when deliberately refreshing the port: hand-maintained Paper adaptations live
alongside imported sources and subsequent changes to imported files are reviewed diffs.
No network or floating upstream revisions are used.
"""
from pathlib import Path
import hashlib
import json
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / 'integrations/lbr-structures/src/main'
SOURCE = ROOT / 'third-party/yungs'


def verify(source):
    manifest = json.loads((source / 'SOURCE.json').read_text())
    for name, expected in manifest['files'].items():
        if hashlib.sha256((source / 'upstream' / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f'Upstream snapshot changed: {source.name}/{name}')


def adapt_accessors(text):
    # Accessor interfaces describe actual NMS fields. Replace Mixin casts with
    # a checked reflection view, preserving the upstream algorithms.
    pattern = re.compile(r'\(\(([A-Za-z]+Accessor)\)\s*')
    while match := pattern.search(text):
        start = match.end()
        end, depth = start, 0
        while end < len(text):
            if text[end] == '(':
                depth += 1
            elif text[end] == ')':
                if depth == 0:
                    break
                depth -= 1
            end += 1
        if end == len(text):
            raise ValueError('Unbalanced accessor expression')
        text = text[:match.start()] + 'com.lunarboreal.structures.nms.PaperAccess.view(' + text[start:end] + ', ' + match[1] + '.class)' + text[end + 1:]
    text = re.sub(r'\(([A-Za-z]+Accessor)\)\s*([a-zA-Z][\w.]*)\s*;',
                  r'com.lunarboreal.structures.nms.PaperAccess.view(\2, \1.class);', text)
    return text


def include_java(relative, family):
    path = relative.as_posix()
    if relative.name == 'package-info.java':
        return False
    if '/mixin/' in path:
        return relative.name.endswith('Accessor.java') and not any(x in relative.name for x in ('Creative', 'Potion'))
    if family == 'api':
        return (any('/' + p + '/' in path for p in ('world', 'codec', 'math', 'json', 'noise'))
                or relative.name in ('YungJigsawManager.java', 'YungsApiCommon.java', 'AutoRegister.java', 'BoxOctree.java',
                                     'StructureTypeModule.java', 'StructurePoolElementTypeModule.java', 'StructurePlacementTypeModule.java')
                or '/api/world/randomize/' in path) and not any(p in path for p in ('/locate/', '/beardifier/', '/world/structure/processor/'))
    return '/services/' not in path and relative.name not in ('CommandModule.java', 'CompatModule.java')


def main():
    count = 0
    for family in (sys.argv[1:] or ('api', 'witch-huts')):
        source = SOURCE / family
        verify(source)
        java = source / 'upstream/Common/src/main/java'
        for file in sorted(java.rglob('*.java')):
            relative = file.relative_to(java)
            if not include_java(relative, family):
                continue
            text = file.read_text()
            if file.name.endswith('Common.java'):
                # Fabric/NeoForge initialization is replaced by StructuresBootstrap.
                text = re.sub(r'\n    public static void init\(\) \{.*?\n    }', '', text, flags=re.S)
                text = re.sub(r'^import .*\.(services|autoregister)\..*;\n', '', text, flags=re.M)
                text = re.sub(r'^import .*\.(YungAutoRegister|LocateReplacer);\n', '', text, flags=re.M)
            text = re.sub(r'^import org\.spongepowered\..*;\n', '', text, flags=re.M)
            text = re.sub(r'^\s*@(Mixin|Accessor|Invoker)(\([^\n]*\))?\s*\n', '\n', text, flags=re.M)
            text = adapt_accessors(text)
            output = DEST / 'java' / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text('// Paper port; original source and LGPL-3.0 notice: third-party/yungs/' + family + '/\n' + text)
            count += 1
        data = source / 'upstream/Common/src/main/resources/data'
        for file in sorted(data.rglob('*')):
            if not file.is_file():
                continue
            relative = file.relative_to(data)
            # Only namespaces relevant to vanilla clients are included.
            namespaces = {'yungsapi', 'bettermineshafts', 'betterdungeons', 'betterstrongholds', 'betterdeserttemples',
                          'betterjungletemples', 'betterwitchhuts', 'betteroceanmonuments', 'betterfortresses', 'betterendisland',
                          'yungsextras', 'yungsbridges', 'minecraft', 'c'}
            if relative.parts[0] not in namespaces:
                continue
            output = DEST / 'resources/yungs/data' / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            if output.exists() and '/tags/' in '/' + relative.as_posix():
                before, after = json.loads(output.read_text()), json.loads(file.read_text())
                for value in after['values']:
                    if value not in before['values']:
                        before['values'].append(value)
                output.write_text(json.dumps(before, indent=2) + '\n')
            else:
                output.write_bytes(file.read_bytes())
    print(f'Imported {count} Java sources from verified snapshots')


if __name__ == '__main__':
    main()
