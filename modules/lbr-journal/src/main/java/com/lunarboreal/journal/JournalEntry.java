package com.lunarboreal.journal;

import java.util.Locale;
import java.util.UUID;

public record JournalEntry(UUID uuid, String playerName, Category category, String key,
                           String label, String world, long firstSeen) {

    public enum Category {
        BIOMES("biomes"), ADVANCEMENTS("advancements"), FISHING("fishing");

        private final String id;

        Category(String id) { this.id = id; }

        public String id() { return id; }

        public static Category parse(String value) {
            for (Category category : values()) {
                if (category.id.equals(value)) return category;
            }
            throw new IllegalArgumentException("Invalid journal category");
        }
    }

    public JournalEntry {
        if (uuid == null || category == null || firstSeen < 0) throw new IllegalArgumentException("Invalid entry");
        playerName = checked(playerName, 64);
        key = checked(key, 255);
        label = checked(label, 255);
        world = checked(world, 128);
    }

    public String id() { return uuid + "/" + category.id() + "/" + key; }

    public static boolean vanillaAdvancement(String namespace, String key) {
        return "minecraft".equals(namespace) && key != null && !key.startsWith("recipes/");
    }

    public static String readableKey(String key) {
        String path = key.substring(key.indexOf(':') + 1);
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        String text = path.replace('_', ' ');
        return text.isEmpty() ? key : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private static String checked(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid journal text");
        return value;
    }
}
