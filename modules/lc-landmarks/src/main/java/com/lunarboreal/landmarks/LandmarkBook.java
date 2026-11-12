package com.lunarboreal.landmarks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

record LandmarkBook(long nextId, List<Landmark> entries) {
    static final int MAX_ENTRIES = 20_000;
    LandmarkBook {
        entries = List.copyOf(entries);
        if (nextId < 1 || nextId > MAX_ENTRIES + 1 || entries.size() > MAX_ENTRIES)
            throw Landmark.problem("invalid");
        var ids = new HashSet<Long>();
        for (Landmark entry : entries)
            if (entry.id() >= nextId || !ids.add(entry.id())) throw Landmark.problem("invalid");
    }
    static LandmarkBook empty() { return new LandmarkBook(1, List.of()); }
    Landmark find(long id) {
        return entries.stream().filter(entry -> entry.id() == id).findFirst()
                .orElseThrow(() -> Landmark.problem("missing"));
    }
    LandmarkBook submit(UUID owner, String ownerName, long landId, String world, int x, int y, int z,
                        String title, String description, int limit, long now) {
        if (entries.size() >= MAX_ENTRIES || nextId > MAX_ENTRIES) throw Landmark.problem("full");
        requireRoom(owner, limit);
        var entry = new Landmark(nextId, owner, ownerName, landId, world, x, y, z, title,
                description, now, now, 1, Landmark.Status.PENDING, "", "");
        var updated = new ArrayList<>(entries);
        updated.add(entry);
        return new LandmarkBook(nextId + 1, updated);
    }
    LandmarkBook edit(long id, UUID actor, long expectedRevision, String title, String description, int limit, long now) {
        Landmark previous = owned(id, actor, expectedRevision);
        if (previous.status() == Landmark.Status.WITHDRAWN) requireRoom(actor, limit);
        return replace(new Landmark(id, previous.owner(), previous.ownerName(), previous.landId(), previous.world(),
                previous.x(), previous.y(), previous.z(), title, description, previous.createdAt(),
                Math.max(now, previous.updatedAt()), previous.revision() + 1, Landmark.Status.PENDING, "", ""));
    }
    LandmarkBook withdraw(long id, UUID actor, long expectedRevision, long now) {
        Landmark previous = owned(id, actor, expectedRevision);
        return withdraw(previous, now, previous.ownerName());
    }
    LandmarkBook moderate(long id, long expectedRevision, Landmark.Status target, String reviewer, String note, long now) {
        Landmark previous = revision(id, expectedRevision);
        if (target == Landmark.Status.WITHDRAWN) return withdraw(previous, now, reviewer);
        if (previous.status() != Landmark.Status.PENDING
                || (target != Landmark.Status.APPROVED && target != Landmark.Status.REJECTED))
            throw Landmark.problem("state");
        if (target == Landmark.Status.REJECTED && (note == null || note.isBlank())) throw Landmark.problem("reason");
        return replace(new Landmark(id, previous.owner(), previous.ownerName(), previous.landId(), previous.world(),
                previous.x(), previous.y(), previous.z(), previous.title(), previous.description(), previous.createdAt(),
                Math.max(now, previous.updatedAt()), previous.revision() + 1, target, reviewer, note));
    }
    private LandmarkBook withdraw(Landmark previous, long now, String reviewer) {
        if (previous.status() == Landmark.Status.WITHDRAWN) throw Landmark.problem("state");
        return replace(new Landmark(previous.id(), previous.owner(), previous.ownerName(), previous.landId(), previous.world(),
                previous.x(), previous.y(), previous.z(), previous.title(), previous.description(), previous.createdAt(),
                Math.max(now, previous.updatedAt()), previous.revision() + 1, Landmark.Status.WITHDRAWN, reviewer, ""));
    }
    private Landmark revision(long id, long expectedRevision) {
        Landmark previous = find(id);
        if (previous.revision() != expectedRevision) throw Landmark.problem("conflict");
        return previous;
    }
    private Landmark owned(long id, UUID actor, long expectedRevision) {
        Landmark previous = revision(id, expectedRevision);
        if (!previous.owner().equals(actor)) throw Landmark.problem("owner");
        return previous;
    }
    private LandmarkBook replace(Landmark entry) {
        return new LandmarkBook(nextId, entries.stream().map(old -> old.id() == entry.id() ? entry : old).toList());
    }
    private void requireRoom(UUID owner, int limit) {
        if (entries.stream().filter(entry -> entry.owner().equals(owner)
                && entry.status() != Landmark.Status.WITHDRAWN).count() >= limit)
            throw Landmark.problem("limit");
    }
}
