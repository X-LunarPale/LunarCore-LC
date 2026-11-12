package com.lunarboreal.landmarks;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class LandmarkRequests {
    record Query(int page, int size, String search, Landmark.Status status, String sort, boolean descending) {}
    record Review(long id, long revision, String reason) {}
    private LandmarkRequests() {}
    static Query query(String page, String size, String search, String status, String sort, String order) {
        int p = integer(page, 1, 1_000_000);
        int s = integer(size, 20, 100);
        String q = search == null ? "" : Landmark.text(search, 0, 120).toLowerCase(Locale.ROOT);
        Landmark.Status filter = null;
        if (status != null && !status.equals("all")) {
            try { filter = Landmark.Status.valueOf(status.toUpperCase(Locale.ROOT)); }
            catch (RuntimeException error) { throw Landmark.problem("query"); }
        }
        String sorting = sort == null ? "id" : sort;
        if (!Set.of("id", "updatedAt").contains(sorting)) throw Landmark.problem("query");
        if (order != null && !Set.of("asc", "desc").contains(order)) throw Landmark.problem("query");
        return new Query(p, s, q, filter, sorting, order == null || order.equals("desc"));
    }
    static Review review(String pathId, Map<String, Object> body, boolean rejecting) {
        long id;
        try { id = Long.parseLong(pathId); } catch (RuntimeException error) { throw Landmark.problem("invalid"); }
        if (id < 1 || id > LandmarkBook.MAX_ENTRIES || body == null
                || !Set.of("row", "reason").containsAll(body.keySet())
                || !(body.get("row") instanceof Map<?, ?> row)
                || number(row.get("id")) != id) throw Landmark.problem("invalid");
        long revision = number(row.get("revision"));
        Object reason = body.getOrDefault("reason", "");
        if (!(reason instanceof String text)) throw Landmark.problem("text");
        String note = Landmark.text(text, rejecting ? 1 : 0, 240);
        return new Review(id, revision, note);
    }
    static String[] submission(String[] args, int start) {
        String[] parts = String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length)).split("\\|", -1);
        if (parts.length != 2) throw Landmark.problem("usage");
        return new String[]{Landmark.text(parts[0].strip(), 1, 48), Landmark.text(parts[1].strip(), 0, 240)};
    }
    static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id >= 1 && id <= LandmarkBook.MAX_ENTRIES) return id;
        } catch (NumberFormatException ignored) {}
        throw Landmark.problem("invalid");
    }
    private static int integer(String value, int fallback, int max) {
        if (value == null) return fallback;
        try {
            int number = Integer.parseInt(value);
            if (number >= 1 && number <= max) return number;
        } catch (NumberFormatException ignored) {}
        throw Landmark.problem("query");
    }
    private static long number(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue()) || number.doubleValue() < 1
                || number.doubleValue() > 9_007_199_254_740_991D) throw Landmark.problem("invalid");
        return number.longValue();
    }
}
