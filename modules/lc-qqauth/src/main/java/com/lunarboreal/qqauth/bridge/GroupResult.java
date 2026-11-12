package com.lunarboreal.qqauth.bridge;

public record GroupResult(boolean handled, String reply) {
    public GroupResult { reply = handled && reply != null ? reply : ""; }
    public static GroupResult ignored() { return new GroupResult(false, ""); }
    public static GroupResult reply(String text) { return new GroupResult(true, text); }
}
