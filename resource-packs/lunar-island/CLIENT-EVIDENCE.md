# 26.3 客户端字体证据及修复边界

本文件为官方客户端JAR的javap字节码与原始shader资产检查，不是REA输出，也不是客户端截图/运行验收。

官方版本清单：https://piston-meta.mojang.com/mc/game/version_manifest_v2.json

26.3版本描述：https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json

客户端下载：https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar

官方SHA1 e877b6a07acd633fb3bb475002175cec036e7b87；本次SHA256 4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d。版本26.3，2026-09-15发布，Java25。只临时下载诊断，不分发客户端JAR。

## 确定的原始缺陷

`net.minecraft.client.gui.font.FontTexture`构造函数字节码offset11、14为sipush256，offset17构造DynamicAtlasTree(0,0,256,256)；offset52、55创建256×256 GPU纹理。`add(GlyphInfo,GlyphBitmap)` offset20/26获取源pixelWidth/Height，offset32调用tree.insert(width,height,1)，offset183返回null。原320×30单glyph不能装入任何256×256字形atlas；这不是PNG整sheet最大GPU尺寸问题。原实现漏检这一限制。

修复：E000左160×30、E002右160×30，两者advance161，中间space(-1)使实际拼接无空缝，总advance321；后退321重新起点。高度/ascent仍30/20。

原mono在每个16×16 cell最右列设置alpha1 sentinel。`BitmapProvider$Definition.getActualGlyphWidth`逐列反向调用NativeImage.getLuminanceOrAlpha，非零像素即有效；其加载过程按round(actualWidth*scale)+1决定advance，scale=height/sourceCellHeight。本次scale1，所以实际非透明最右x+1再加1，原来ASCII也恒17。窄TPS字形的视觉空隙因此很大。这是人为强制等宽设计导致，不是space负advance被禁止。

修复：去sentinel，墨迹左对齐x1，保留正常抗锯齿；逐cell alpha非零bbox右边界+1写widths.properties（大写HEX无前缀），Java按此合计而非每字17。ASCII U+0020显式space provider advance4优先，空白bitmap若无墨迹本来公式为1。不存在的文字服务端替换U+25A1。

## RGB与透明度结论

`BitmapProvider$Glyph$1.isColored()` offsets0..16取NativeImage.format().components()，offset17比较>1，RGBA返回true；upload直接copyBufferToTexture保留多通道像素。`FontTexture`构造offset43 colored选RGBA8_UNORM，非colored才R8_UNORM。RGBA PNG的getLuminanceOrAlpha按alpha通道扫描，并非按黑色RGB亮度判空。

客户端原始`assets/minecraft/shaders/core/text.fsh`：

```glsl
#ifdef IS_GRAYSCALE
vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
vec4 texColor = texture(Sampler0, texCoord0);
#endif
vec4 color = texColor * vertexColor * ColorModulator;
if (color.a < 0.1) { discard; }
```

因此RGBA bitmap走colored RGBA，背景黑RGB保留，Component WHITE保持原色；没有“所有bitmap只渲染白mask”的依据。alpha1约0.0039低于shader0.1会不可见，但仍被CPU扫描计宽。首次背景黑(12,12,16,245)不受此阈值丢弃；垂直布局修复后改为(12,12,16,255)，避免透出原版底条。

## 负space有效

`com.mojang.blaze3d.font.SpaceProvider$Definition.lambda$static$0` offsets1/4取ExtraCodecs.CODEPOINT与Codec.FLOAT，offset7 unboundedMap，未限制非负。`SpaceProvider.lambda$new$0` offset13取Float.floatValue，offset16构造EmptyGlyph(float)，没有截断或取绝对值。E001=-321及二进制负值合法。

## BossBar垂直坐标与最终参数

同一官方26.3客户端的`BossHealthOverlay.extractRenderState`：offset34将首barY设为12；offset96先调用extractBar；offset130..135计算textY=barY-9=3；offset152绘制名称。其extractBar重载offset15..25使用182×5的原版条。

`com.mojang.blaze3d.font.GlyphBitmap.getTop` offsets0..8为`7-getBearingTop()`；getBottom为top+pixelHeight/oversample。Bitmap Glyph$1.getBearingTop读取JSON ascent，scale1时GUI绝对顶部=textY+7-ascent。

旧背景height30/ascent20：GUI top=3+7-20=-10，bottom20；GUIscale2时上20屏幕像素被裁。最终采用背景height30/ascent7：GUI top3、bottom33，屏幕scale2为top6、bottom66。正文height16/ascent0：cell GUI top10、bottom26，处于背景上下各7px的中央区域；实际墨迹继续按cell内部栅格位置显示。这些是从代码计算的范围，不是新客户端截图验收。

背景alpha255，Component WHITE，保持深色RGB。原版先bar后名称的提取顺序使胶囊可覆盖自己原条y12..17所在区域，无需全局替换任何原版纹理。

负ascent在语法上允许：BitmapProvider$Definition.lambda$static$2 offset42取普通Codec.INT、offset47 fieldOf(ascent)；validate offsets0..8只验证ascent<=height。不过最终使用正文ascent0，不需要负值。

多BossBar限制：extractRenderState offsets155..174每次barY增加10+9=19 GUIpx；30px高胶囊超过原版19px行距，多个BossBar仍可能垂直重叠。资源包不能单独改变这一原版布局，应在多BossBar共存场景实测并选择显示策略。

## 验证范围

运行tools/verify-island-pack.py检查所有21393码点生成widths与实际alpha扫描一致、背景源glyph<=256、拼接advance与负space、无minecraft全局覆盖、许可与ZIP完整性。此检查不能证明客户端已收到新SHA包，也不能证明整套BossBar定位已视觉验收。最终应在26.3客户端重新接受新SHA资源包后核验。
