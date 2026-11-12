package com.lunarboreal.titles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

record TitleDefinition(String id, String display, String permission) {
    static final MiniMessage SAFE = MiniMessage.builder().tags(TagResolver.resolver(StandardTags.color(),
            StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(), StandardTags.reset())).build();
    TitleDefinition {
        id = validId(id);
        if (display == null || display.isBlank() || display.length() > 256 || display.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("display 必须为 1–256 字符单行文本");
        if (permission == null || !permission.matches("[A-Za-z0-9_.-]{0,128}")) throw new IllegalArgumentException("权限节点格式无效");
    }
    static String validId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,47}")) throw new IllegalArgumentException("称号 ID 必须为 1–48 位小写字母、数字、_ 或 -");
        return id;
    }
    Component component() { return SAFE.deserialize(display); }
    String safeMini() { return MiniMessage.miniMessage().serialize(component()); }
    String plain() { return PlainTextComponentSerializer.plainText().serialize(component()); }
}
