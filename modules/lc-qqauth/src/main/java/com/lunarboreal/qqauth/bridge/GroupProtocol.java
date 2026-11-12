/* Copyright (c) 2026 QQAuth contributors. SPDX-License-Identifier: MIT
 * Adapted from QQAuth OneBotClient and AstrBotHttpServer; see bridge/LICENSE. */
package com.lunarboreal.qqauth.bridge;

import com.google.gson.*;

/** Transport envelopes only: identities never come from command text or nested sender claims. */
public final class GroupProtocol {
    private GroupProtocol() {}
    public record Message(String group, String sender, String text) {}
    public static Message astrBot(String payload) {
        JsonObject object = JsonParser.parseString(payload).getAsJsonObject();
        return new Message(required(object, "group_id"), required(object, "sender_id"), required(object, "message"));
    }
    public static Message oneBot(String payload) {
        JsonObject object = JsonParser.parseString(payload).getAsJsonObject();
        if (!"message".equals(string(object, "post_type")) || !"group".equals(string(object, "message_type"))) return null;
        String group = numericId(object, "group_id"), sender = numericId(object, "user_id");
        String self = id(object, "self_id");
        if (sender.equals(self)) return null;
        String text = extractText(object);
        return text.isBlank() ? null : new Message(group, sender, text);
    }
    public static String extractText(JsonObject object) {
        String raw = string(object, "raw_message");
        if (raw != null && !raw.isEmpty()) return raw;
        JsonElement message = object.get("message");
        if (message == null) return "";
        if (message.isJsonPrimitive() && message.getAsJsonPrimitive().isString()) return message.getAsString();
        StringBuilder text = new StringBuilder();
        if (message.isJsonArray()) for (JsonElement element : message.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject segment = element.getAsJsonObject();
            if ("text".equals(string(segment, "type")) && segment.has("data") && segment.get("data").isJsonObject()) {
                String part = string(segment.getAsJsonObject("data"), "text");
                if (part != null) text.append(part);
            }
        }
        return text.toString();
    }
    public static String reply(GroupResult result) {
        JsonObject object = new JsonObject();
        object.addProperty("ok", true); object.addProperty("handled", result.handled()); object.addProperty("reply", result.reply());
        return object.toString();
    }
    public static String oneBotReply(String group, String reply) {
        if (!group.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Invalid group");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", new java.math.BigInteger(group));
        // Explicit text segment prevents reply strings being interpreted as CQ actions.
        JsonObject data = new JsonObject(); data.addProperty("text", reply);
        JsonObject segment = new JsonObject(); segment.addProperty("type", "text"); segment.add("data", data);
        JsonArray segments = new JsonArray(); segments.add(segment); params.add("message", segments);
        JsonObject action = new JsonObject(); action.addProperty("action", "send_group_msg"); action.add("params", params);
        return action.toString();
    }
    private static String required(JsonObject object, String key) {
        String value = string(object, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing string field");
        return value.trim();
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    private static String id(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && (value.getAsJsonPrimitive().isString() || value.getAsJsonPrimitive().isNumber()) ? value.getAsString() : null;
    }
    private static String numericId(JsonObject object, String key) {
        String value = id(object, key);
        if (value == null || !value.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Invalid identity");
        return value;
    }
}
