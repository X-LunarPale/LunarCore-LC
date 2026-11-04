"""Apply mechanical, reviewable 26.1.2 -> 26.3 source/data migrations to the Paper port.

Never modifies third-party/yungs (the original snapshots). Semantic runtime
adaptations are maintained in Java, separately from these symbol/codec changes.
"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parent.parent / 'integrations/lbr-structures/src/main'
COLORS = 'WHITE|ORANGE|MAGENTA|LIGHT_BLUE|YELLOW|LIME|PINK|GRAY|LIGHT_GRAY|CYAN|PURPLE|BLUE|BROWN|GREEN|RED|BLACK'
GROUPS = 'STAINED_GLASS_PANE|STAINED_GLASS|WALL_BANNER|BANNER|TERRACOTTA|GLAZED_TERRACOTTA|CONCRETE_POWDER|CONCRETE|WOOL|CARPET|SHULKER_BOX|BED|CANDLE_CAKE|CANDLE'


def migrate(text):
    text = text.replace('import javax.annotation.ParametersAreNonnullByDefault;', '').replace('@ParametersAreNonnullByDefault', '')
    text = text.replace('extends StructureProcessor {', 'extends com.lunarboreal.structures.nms.LegacyStructureProcessor {')
    text = re.sub(r'StructureProcessorType<([^>]+)>', r'com.mojang.serialization.MapCodec<\1>', text)
    text = text.replace('MapCodec<?> getType()', 'MapCodec<? extends StructureProcessor> getType()')
    text = re.sub(r'(MapCodec<[^>]+>\s+\w+\s*=\s*)\(\) -> ([^;]+);', r'\1\2;', text)
    text = text.replace('.info().pos()', '.pos()').replace('.info().state()', '.state()')
    text = text.replace('JigsawBlockInfo.of(', 'JigsawBlockInfo.parse(')
    text = re.sub(r'\b(Blocks|Items)\.('+COLORS+r')_('+GROUPS+r')\b',
                  lambda m: m[1]+'.'+m[3]+'.pick(net.minecraft.world.item.DyeColor.'+m[2]+')', text)
    text = text.replace('Blocks.CHAIN', 'Blocks.IRON_CHAIN').replace('Items.CHAIN', 'Items.IRON_CHAIN')
    # CHAINMAIL is an armor material, not the chain block.
    text = text.replace('IRON_CHAINMAIL', 'CHAINMAIL')
    text = text.replace('net.minecraft.core.RegistryCodecs', 'net.minecraft.core.registries.codec.RegistryCodecs')
    text = text.replace('RegistryCodecs.homogeneousList(', 'RegistryCodecs.holderSet(')
    text = text.replace('placement.StructurePlacement.ExclusionZone', 'placement.AbstractSpreadingStructurePlacement.ExclusionZone')
    return text


def data_migration(value):
    if isinstance(value, list):
        return [data_migration(v) for v in value]
    if not isinstance(value, dict):
        return value
    if 'Name' in value and set(value) <= {'Name', 'Properties'}:
        value['id'] = value.pop('Name')
        if 'Properties' in value:
            value['properties'] = value.pop('Properties')
    if 'minCount' in value and 'maxCount' in value:
        low, high = value.pop('minCount'), value.pop('maxCount')
        value['count'] = low if low == high else {'type': 'minecraft:uniform', 'min_inclusive': low, 'max_inclusive': high}
    if isinstance(value.get('condition'), str) and value['condition'].startswith('minecraft:'):
        value['type'] = value.pop('condition')
    if value.get('type') == 'minecraft:entity_properties' and isinstance(value.get('predicate'), dict):
        predicate = value['predicate']
        for key in ('location', 'equipment'):
            if key in predicate:
                predicate['minecraft:' + key] = predicate.pop(key)
        if 'type' in predicate:
            predicate['minecraft:entity_type'] = predicate.pop('type')
    if isinstance(value.get('conditions'), dict):
        for key, predicates in list(value['conditions'].items()):
            if isinstance(predicates, list) and all(isinstance(p, dict) and ('condition' in p or 'type' in p) for p in predicates):
                value['conditions'][key] = predicates[0] if len(predicates) == 1 else {'type': 'minecraft:all_of', 'terms': predicates}
    return {key: data_migration(item) for key, item in value.items()}


if __name__ == '__main__':
    for path in (ROOT / 'java/com/yungnickyoung').rglob('*.java'):
        before = path.read_text()
        after = migrate(before)
        if after != before:
            path.write_text(after)
    for path in (ROOT / 'resources/yungs/data').rglob('*.json'):
        data = json.loads(path.read_text())
        path.write_text(json.dumps(data_migration(data), indent=2) + '\n')
