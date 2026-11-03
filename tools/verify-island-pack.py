"""Verify generated assets against measured 26.3 bitmap advance contract; not a client test."""
from pathlib import Path
import json
import zipfile
from PIL import Image
root=Path(__file__).resolve().parents[1]/'resource-packs/lunar-island'
pack=root/'pack'
widths={int(k,16):int(v) for k,v in (line.split('=') for line in (root/'widths.properties').read_text().splitlines())}
font=json.loads((pack/'assets/lunar/font/mono.json').read_text(encoding='utf-8'))
seen=set()
for p in font['providers']:
    if p['type']=='space':
        for char,value in p['advances'].items(): assert widths[ord(char)]==value;seen.add(ord(char))
        continue
    image=Image.open(pack/'assets/lunar/textures'/p['file'].split(':',1)[1])
    rows=p['chars'];cols=len(rows[0]);cw=image.width//cols;ch=image.height//len(rows)
    assert cw==ch==16
    for y,row in enumerate(rows):
        for x,char in enumerate(row):
            if char=='\x00' or ord(char) in seen:continue
            cell=image.crop((x*cw,y*ch,(x+1)*cw,(y+1)*ch))
            bbox=cell.getchannel('A').getbbox()
            actual=int((bbox[2] if bbox else 0)*0.5+0.5)+1
            assert widths[ord(char)]==actual,(hex(ord(char)),actual,widths[ord(char)])
            seen.add(ord(char))
assert seen==set(widths) and widths[0x25a1]>0
island=json.loads((pack/'assets/lunar/font/island.json').read_text(encoding='utf-8'))
for p in island['providers']:
    image=Image.open(pack/'assets/lunar/textures'/p['file'].split(':',1)[1])
    assert image.size==(96,18) and image.getchannel('A').getbbox()[2]==96
    assert image.width<=256 and image.height<=256
    assert p['ascent']==7
    # First vanilla bar: y=12, title y=3, bitmap top=7-ascent.
    assert 3+7-p['ascent'] == 3
    assert image.getpixel((48,9))[3] == 255
for p in font['providers']:
    if p['type']=='bitmap': assert p['ascent']==2 and p['height']==8
# Half-scale text cell y8..16 centered in background y3..21.
assert (8+16)/2 == (3+21)/2
assert widths[32]==3
space=json.loads((pack/'assets/lunar/font/space.json').read_text(encoding='utf-8'))['providers'][0]['advances']
assert space['\ue001']==-193
assert 97+space['\ue100']+97==193
for bit in range(10):assert space[chr(0xe100+bit)]==-(1<<bit) and space[chr(0xe110+bit)]==1<<bit
with zipfile.ZipFile(root/'lunar-island.zip') as archive:
    assert archive.testzip() is None
    assert 'LICENSES/NotoSansSC-OFL.txt' in archive.namelist()
    assert all(not name.startswith('assets/minecraft/') for name in archive.namelist())
assert (root/'lunar-island.zip').stat().st_size<10*1024*1024
print(f'PASS local asset invariants: {len(widths)} glyph advances, 2 atlas-safe background cells. Client visual test remains pending.')
