# Lunar Island：26.3 修复测试版

产物[lunar-island.zip](<lunar-island.zip>)，生成入口[build-island-pack.py](<../../tools/build-island-pack.py>)；本地不变量检查[verify-island-pack.py](<../../tools/verify-island-pack.py>)。需要Python/Pillow。默认NotoSansSC.ttf来自[Google Fonts](https://github.com/google/fonts/tree/main/ofl/notosanssc)，SIL OFL1.1，许可[OFL.txt](<OFL.txt>)也打包至LICENSES/NotoSansSC-OFL.txt，不分发源TTF。相同字体/Pillow/脚本输出固定字节ZIP，来源hash在[build-manifest.json](<build-manifest.json>)。

本包仅assets/lunar命名空间，无minecraft全局BossBar或GUI覆盖。背景黑(12,12,16,245)，Component使用WHITE保留原色；正文白色。[资产预览](<pack-preview.png>)不是客户端截图。尚待26.3客户端实际验收。

## 修复及度量

官方客户端字节码证明：每个baked glyph仅能装入256×256 atlas，原320×30单glyph不可用；原alpha1 sentinel使ASCII固定17宽，造成窄字间距过大。证据、来源SHA与方法offset见[CLIENT-EVIDENCE.md](<CLIENT-EVIDENCE.md>)。

- `lunar:island` U+E000左、U+E002右，各源160×30，height30/ascent20，各有效宽160/advance161。序列左→space(-1)→右，总视觉宽320/advance321。
- `lunar:mono`仍保留字体名，已改实际比例宽度。源16×16，height16/ascent13，无sentinel；墨迹左x1，最右非零alpha边界+1为advance（scale1）。禁止bold。具体每码点宽度见[widths.properties](<widths.properties>)或[widths.json](<widths.json>)，HEX无前缀整数。ASCII空格由space provider优先定义advance4。
- 覆盖ASCII32..126、CJK4E00..9FFF、3000..303F、FF00..FFEF、FFFD、25A1，共21393码点；未知字替□，不混用其他font估宽。
- `lunar:space` U+E001=-321；E100..E109=-1,-2,-4,...,-512；E110..E119=+1,+2,+4,...,+512。任意整数偏移按二进制分解，必要时重复512。
- 文本上限由Java控制：最多32码点且总宽W<=272，按widths合计。左L=floor((320-W)/2)，右R=320-W-L。完整序列：E000(island)→E100(space,-1)→E002(island)→E001(space,-321)→offset(+L)→text(mono)→offset(+R)。总advance320。

## 格式与验收

[pack.mcmeta](<pack/pack.mcmeta>)为min_format/max_format [97,1]，与目标26.3 version.json一致。每页1024×1024最多4096字，但实际上传按单glyph16×16进行；背景每glyph160×30，符合256约束。RGBA bitmap保留RGB，shader不会把黑背景变成白mask。原版BossBar底条的显示/隐藏需Java侧另行处理。

本地验证逐字检查widths与alpha扫描、背景单glyph尺寸、space进退与ZIP许可/完整性、无全局覆盖；不等同客户端验收。请重新接受新SHA包后检查TPS 14.46、中英文、空格、□、多种GUI缩放和其他BossBar共存；按Java实际截图评估最终居中。不要继续使用旧SHA缓存。
