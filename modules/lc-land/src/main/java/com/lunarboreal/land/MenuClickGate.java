package com.lunarboreal.land;

/** Main-thread, per-menu one-shot gate; consume before scheduling any world/economy mutation. */
final class MenuClickGate {
    private boolean consumed;
    boolean consume() {
        if (consumed) return false;
        consumed = true;
        return true;
    }
}
