package com.lunarboreal.qqauth.bridge;

/** Invoked off the server thread. Implementations must enforce the group allowlist,
 * use only the supplied transport sender identity, and marshal game actions safely. */
@FunctionalInterface
public interface GroupProcessor {
    GroupResult handle(String group, String sender, String message);
}
