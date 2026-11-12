"""Embed the exact generated optional pack and its digest in the scoreboard module."""
from pathlib import Path
import hashlib
import shutil
root = Path(__file__).resolve().parents[1]
archive = root / 'resource-packs/lunar-island/lunar-island.zip'
resource = root / 'modules/lc-scoreboard/src/main/resources'
(resource / 'web').mkdir(parents=True, exist_ok=True)
shutil.copy2(archive, resource / 'web/island-pack.zip')
shutil.copy2(root / 'resource-packs/lunar-island/widths.properties', resource / 'island-widths.properties')
sha1 = hashlib.sha1(archive.read_bytes()).hexdigest()
(resource / 'island-pack.properties').write_text('sha1=' + sha1 + '\n', encoding='ascii')
print('Embedded resource pack SHA1:', sha1)
