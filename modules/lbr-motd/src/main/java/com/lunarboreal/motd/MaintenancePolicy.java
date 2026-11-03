package com.lunarboreal.motd;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Immutable admission/eviction policy shared by login and administrative actions. */
final class MaintenancePolicy {
    private final MaintenanceSettings settings;
    private final Set<String> whitelist;

    MaintenancePolicy(MaintenanceSettings settings) {
        this.settings = settings;
        this.whitelist = Set.copyOf(settings.whitelist());
    }

    boolean exempt(String name, UUID uuid) {
        return (uuid != null && whitelist.contains(uuid.toString()))
                || (name != null && whitelist.contains(name.toLowerCase(Locale.ROOT)));
    }

    boolean rejectJoin(boolean alreadyAllowed, String name, UUID uuid) {
        return alreadyAllowed && settings.enabled() && !settings.allowJoin() && !exempt(name, uuid);
    }

    // Manual eviction is explicit and intentionally does not require the automatic kick option.
    boolean kickOnRequest(String name, UUID uuid) { return settings.enabled() && !exempt(name, uuid); }

    static boolean kickAfterSave(MaintenanceSettings before, MaintenanceSettings after) {
        return after.enabled() && after.kickOnline() && (!before.enabled() || !before.kickOnline());
    }

    static boolean displaysMotd(MotdSettings settings) { return settings.maintenance().enabled() || settings.enabled(); }
}
