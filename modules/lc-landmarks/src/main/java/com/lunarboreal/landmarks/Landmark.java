package com.lunarboreal.landmarks;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

record Landmark(long id, UUID owner, String ownerName, long landId, String world,
                int x, int y, int z, String title, String description,
                long createdAt, long updatedAt, long revision, Status status,
                String reviewer, String reviewNote) {
    enum Status {
        PENDING("待审核"), APPROVED("已公开"), REJECTED("已驳回"), WITHDRAWN("已撤回");
        final String label;
        Status(String label) { this.label = label; }
    }

    Landmark {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(status);
        ownerName = text(ownerName, 1, 64);
        world = text(world, 1, 128);
        title = text(title, 1, 48);
        description = text(description, 0, 240);
        reviewer = text(reviewer, 0, 64);
        reviewNote = text(reviewNote, 0, 240);
        if (id < 1 || landId < 1 || createdAt < 1 || updatedAt < createdAt || revision < 1
                || Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000
                || y < -2048 || y > 2047) throw problem("invalid");
    }

    static IllegalArgumentException problem(String key) {
        return new IllegalArgumentException(key);
    }

    static String text(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max
                || (min > 0 && value.isBlank())
                || value.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT || c == '<' || c == '>' || c == 0xA7))
            throw problem("text");
        return value.strip();
    }

    String searchable() {
        return (id + " " + title + " " + description + " " + ownerName + " " + owner + " " + world)
                .toLowerCase(Locale.ROOT);
    }
}
