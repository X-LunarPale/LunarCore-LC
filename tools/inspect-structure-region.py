"""Read existing Anvil chunks without Minecraft dependencies or modifying the save.

Usage: python tools/inspect-structure-region.py WORLD_REGION_DIRECTORY CHUNK_X CHUNK_Z
"""
import gzip
import io
import json
from pathlib import Path
import struct
import sys
import zlib


def read_nbt(data):
    stream = io.BytesIO(data)

    def read(fmt):
        size = struct.calcsize(fmt)
        raw = stream.read(size)
        if len(raw) != size:
            raise ValueError('Truncated NBT')
        return struct.unpack(fmt, raw)[0]

    def string():
        return stream.read(read('>H')).decode('utf-8')

    def payload(kind, depth=0):
        if depth > 512:
            raise ValueError('NBT nesting limit exceeded')
        if kind in (1, 2, 3, 4, 5, 6):
            return read({1: '>b', 2: '>h', 3: '>i', 4: '>q', 5: '>f', 6: '>d'}[kind])
        if kind == 8:
            return string()
        if kind in (7, 9, 11, 12):
            element = read('>B') if kind == 9 else {7: 1, 11: 3, 12: 4}[kind]
            size = read('>i')
            if size < 0 or size > 16777216:
                raise ValueError('Invalid NBT array length')
            return [payload(element, depth + 1) for _ in range(size)]
        if kind == 10:
            result = {}
            while element := read('>B'):
                name = string()
                result[name] = payload(element, depth + 1)
            return result
        raise ValueError(f'Unsupported NBT type {kind}')

    kind = read('>B')
    string()
    return payload(kind)


def read_chunk(directory, x, z):
    path = directory / f'r.{x // 32}.{z // 32}.mca'
    with path.open('rb') as stream:
        stream.seek(4 * ((x & 31) + 32 * (z & 31)))
        location = int.from_bytes(stream.read(4), 'big')
        if location == 0:
            raise ValueError('Chunk has not been saved')
        stream.seek((location >> 8) * 4096)
        size = int.from_bytes(stream.read(4), 'big')
        kind = stream.read(1)[0]
        if kind & 128:
            data = (directory / f'c.{x}.{z}.mcc').read_bytes()
            kind &= 127
        else:
            if size > 64 * 1024 * 1024:
                raise ValueError('Chunk exceeds inspection limit')
            data = stream.read(size - 1)
        if kind == 1:
            data = gzip.decompress(data)
        elif kind == 2:
            data = zlib.decompress(data)
        elif kind != 3:
            raise ValueError(f'Unsupported region compression {kind}')
        return read_nbt(data)


def inspect(directory, x, z):
    chunk = read_chunk(directory, x, z)
    result = {key: chunk.get(key) for key in ('xPos', 'zPos', 'Status', 'DataVersion', 'structures')}
    result['block_entities'] = chunk.get('block_entities', [])
    counts = {}
    for section in chunk.get('sections', []):
        states = section.get('block_states', {})
        palette = states.get('palette', [])
        if not palette:
            continue
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        for index in range(4096):
            selected = 0 if len(palette) == 1 else (states['data'][index // per_long] >> ((index % per_long) * bits)) & ((1 << bits) - 1)
            state = palette[selected]
            name = state if isinstance(state, str) else state.get('id', state.get('Name', state.get('')))
            if name is None:
                raise ValueError(f'Unknown block-state palette entry: {state}')
            counts[name] = counts.get(name, 0) + 1
    result['blocks'] = dict(sorted(counts.items()))
    return result


if __name__ == '__main__':
    print(json.dumps(inspect(Path(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])), indent=2, ensure_ascii=False))
