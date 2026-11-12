"""Verify served island resource pack integrity without client credentials."""
import hashlib
import io
import json
import os
import urllib.request
import zipfile
from pathlib import Path
local = Path('resource-packs/lunar-island/lunar-island.zip')
url = os.environ.get('LC_ISLAND_PACK_URL', 'http://localhost:8765/ext/scoreboard/island-pack.zip')
with urllib.request.urlopen(url, timeout=20) as response:
    payload = response.read()
assert payload == local.read_bytes(), 'Served ZIP differs from artifact'
with zipfile.ZipFile(io.BytesIO(payload)) as archive:
    assert archive.testzip() is None
    meta = json.loads(archive.read('pack.mcmeta'))
    for name in ('assets/lunar/font/island.json', 'assets/lunar/font/space.json', 'assets/lunar/font/mono.json'):
        assert 'providers' in json.loads(archive.read(name))
    assert not any(name.startswith('assets/minecraft/') for name in archive.namelist())
    from PIL import Image
    for name in ('assets/lunar/font/island.json', 'assets/lunar/font/mono.json'):
        for provider in json.loads(archive.read(name))['providers']:
            if provider['type'] != 'bitmap':
                continue
            namespace, relative = provider['file'].split(':', 1)
            image = Image.open(io.BytesIO(archive.read(f'assets/{namespace}/textures/{relative}')))
            rows = provider['chars']
            cell_width = image.width // len(rows[0])
            cell_height = image.height // len(rows)
            assert cell_width <= 256 and cell_height <= 256, (name, cell_width, cell_height)
    print('All individual bitmap glyph cells fit client 256px atlas')
print('Resource pack HTTP 200; exact bytes and ZIP integrity verified')
print('SHA1:', hashlib.sha1(payload).hexdigest())
print('Pack metadata:', meta)
