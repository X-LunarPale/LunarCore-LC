"""Build the console-only local acceptance probe against an already bootstrapped Paper 26.3.

python tools/structures-probe/build.py /absolute/path/to/paper-home
JAVA_HOME must point to Java 25. This JAR is not included in release bundles.
"""
import os
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent
home = Path(sys.argv[1]).resolve()
native = home / 'versions/26.3/paper-26.3.jar'
if not native.is_file():
    raise SystemExit(f'Bootstrap Paper first: missing {native}')
java = Path(os.environ['JAVA_HOME']) / 'bin'
classes = root / 'build/classes'
classes.mkdir(parents=True, exist_ok=True)
digest = hashlib.sha256(native.read_bytes()).hexdigest()
libraries = None
for bundle in sorted(home.glob('*.jar')):
    with zipfile.ZipFile(bundle) as archive:
        if 'META-INF/versions.list' not in archive.namelist():
            continue
        versions = archive.read('META-INF/versions.list').decode().splitlines()
        if not any(row.split('\t')[0] == digest for row in versions):
            continue
        libraries = [home / 'libraries' / row.split('\t')[-1]
                     for row in archive.read('META-INF/libraries.list').decode().splitlines() if row]
        break
if libraries is None or not all(path.is_file() for path in libraries):
    raise SystemExit('Keep the matching Paper bundle in the server directory and bootstrap it first')
classpath = os.pathsep.join(map(str, [native, *libraries]))
(root / 'build/classpath.txt').write_text(os.pathsep.join([str(classes), classpath]))
subprocess.run([str(java / 'javac'), '--release', '25', '-cp', classpath, '-d', str(classes),
                *map(str, (root / 'src/main/java').rglob('*.java'))], check=True)
shutil.copyfile(root / 'src/main/resources/plugin.yml', classes / 'plugin.yml')
subprocess.run([str(java / 'jar'), '--create', '--file', str(root / 'build/StructuresProbe.jar'), '-C', str(classes), '.'], check=True)
print(root / 'build/StructuresProbe.jar')
