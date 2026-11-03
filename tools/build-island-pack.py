"""Build the original Lunar island pack. Requires Pillow; no global Minecraft overrides.
Usage: python tools/build-island-pack.py [--font resource-packs/lunar-island/NotoSansSC.ttf]
"""
from pathlib import Path
import argparse
import hashlib
import json
import zipfile
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'resource-packs' / 'lunar-island'
PACK = OUT / 'pack'

def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--font', default=str(OUT / 'NotoSansSC.ttf'))
    args = parser.parse_args()
    source = Path(args.font)
    face = ImageFont.truetype(str(source), 13, index=0)
    textures = PACK / 'assets/lunar/textures/font'
    textures.mkdir(parents=True, exist_ok=True)
    save_json(PACK / 'pack.mcmeta', {'pack': {
        'description': 'Lunar Island · 胶囊背景与等宽字体 · 26.3',
        'min_format': [97, 1], 'max_format': [97, 1]
    }})
    bg = Image.new('RGBA', (192, 18))
    draw = ImageDraw.Draw(bg)
    draw.rounded_rectangle((0, 0, 191, 17), radius=9, fill=(12, 12, 16, 255))
    bg.save(textures / 'island.png', optimize=False, compress_level=9)
    bg.crop((0,0,96,18)).save(textures / 'island-left.png')
    bg.crop((96,0,192,18)).save(textures / 'island-right.png')
    save_json(PACK / 'assets/lunar/font/island.json', {'providers': [
        {'type':'bitmap','file':'lunar:font/island-left.png','height':18,'ascent':7,'chars':['\ue000']},
        {'type':'bitmap','file':'lunar:font/island-right.png','height':18,'ascent':7,'chars':['\ue002']}
    ]})
    advances = {'\ue001': -193}
    for bit in range(10):
        advances[chr(0xe100 + bit)] = -(1 << bit)
        advances[chr(0xe110 + bit)] = 1 << bit
    save_json(PACK / 'assets/lunar/font/space.json', {'providers': [{'type': 'space', 'advances': advances}]})
    chars = list(range(32, 127)) + list(range(0x4e00, 0xa000)) + list(range(0x3000, 0x3040)) + list(range(0xff00, 0xfff0)) + [0xfffd, 0x25a1]
    providers = [{'type':'space','advances':{' ':3}}]
    widths = {32:3}
    for page, offset in enumerate(range(0, len(chars), 4096)):
        codes = chars[offset:offset+4096]
        cols = 64
        rows = (len(codes)+cols-1)//cols
        atlas = Image.new('RGBA', (cols*16, rows*16))
        for i, cp in enumerate(codes):
            cell = Image.new('RGBA', (16, 16))
            glyph = Image.new('RGBA', (32, 32))
            d = ImageDraw.Draw(glyph)
            text = chr(cp)
            box = d.textbbox((0, 0), text, font=face)
            width, height = box[2]-box[0], box[3]-box[1]
            if width and height:
                d.text((-box[0], -box[1]), text, font=face, fill=(255, 255, 255, 255))
                ink = glyph.crop((0, 0, width, height))
                if ink.width > 14 or ink.height > 14:
                    ink.thumbnail((14, 14), Image.Resampling.LANCZOS)
                cell.alpha_composite(ink, (1, max(0, (15-ink.height)//2)))
            bounds = cell.getchannel('A').getbbox()
            if cp != 32: widths[cp] = int((bounds[2] if bounds else 0) * 0.5 + 0.5) + 1
            atlas.alpha_composite(cell, ((i%cols)*16, (i//cols)*16))
        image_name = f'mono-{page:02}.png'
        atlas.save(textures / image_name, optimize=False, compress_level=9)
        code_rows = []
        for i in range(rows):
            row = codes[i*cols:(i+1)*cols]
            code_rows.append(''.join(chr(cp) for cp in row) + '\x00'*(cols-len(row)))
        providers.append({'type': 'bitmap', 'file': f'lunar:font/{image_name}', 'height': 8, 'ascent': 2, 'chars': code_rows})
    save_json(PACK / 'assets/lunar/font/mono.json', {'providers': providers})
    properties = ''.join(f'{cp:04X}={widths[cp]}\n' for cp in sorted(widths))
    (OUT / 'widths.properties').write_text(properties,encoding='ascii')
    (PACK / 'assets/lunar/font/widths.properties').write_text(properties,encoding='ascii')
    save_json(OUT / 'widths.json', {f'{cp:04X}':widths[cp] for cp in sorted(widths)})
    (PACK / 'LICENSES').mkdir(exist_ok=True)
    (PACK / 'LICENSES' / 'NotoSansSC-OFL.txt').write_bytes((OUT / 'OFL.txt').read_bytes())
    sample = 'TPS 14.46 琉月冰澜'
    preview = Image.new('RGBA', (400, 100), (77, 101, 116, 255))
    preview.alpha_composite(bg, (40, 35))
    lookup = {cp:i for i,cp in enumerate(chars)}
    x = 40 + (192-sum(widths[ord(ch)] for ch in sample))//2
    for ch in sample:
        index = lookup[ord(ch)]
        page, within = divmod(index,4096)
        with Image.open(textures / f'mono-{page:02}.png') as sheet:
            cell = sheet.crop(((within%64)*16,(within//64)*16,(within%64)*16+16,(within//64)*16+16))
            preview.alpha_composite(cell.resize((8,8), Image.Resampling.NEAREST),(x,40))
        x += widths[ord(ch)]
    ImageDraw.Draw(preview).text((12,12),'ASSET PREVIEW - NOT A CLIENT SCREENSHOT',fill='white')
    preview.save(OUT / 'pack-preview.png')
    archive = OUT / 'lunar-island.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in sorted(PACK.rglob('*')):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), date_time=(2026,1,1,0,0,0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                z.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (OUT / 'lunar-island.zip.sha256').write_text(digest+'  '+archive.name+'\n', encoding='utf-8')
    save_json(OUT / 'build-manifest.json', {
        'resource_format': [97,1], 'background': {'width':192,'height':18,'ascent':7,'expected_advance':193,'halves':2,'source_cell':[96,18],'half_advance':97,'join_offset':-1},
        'mono': {'codepoints':len(chars),'cell':[16,16],'height':8,'ascent':2,'widths':'widths.properties','sentinel_alpha':None,'space_advance':3},
        'font_source_name':source.name,'font_source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
        'pillow_version':__import__('PIL').__version__, 'archive_sha256':digest,
        'license_note':'Noto Sans SC, SIL OFL 1.1; pack contains raster assets and OFL.txt, not source TTF.'
    })
    print(f'Built {archive}: {len(chars)} glyphs; SHA256 {digest}')

if __name__ == '__main__': main()
