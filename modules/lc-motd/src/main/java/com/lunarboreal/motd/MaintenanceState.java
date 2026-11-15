package com.lunarboreal.motd;

import java.time.Instant;

/** Effective state is derived from the selected control mode, never written back to configuration. */
record MaintenanceState(boolean active, boolean scheduled, String phase, String nextTransitionAt) {
    static MaintenanceState at(MaintenanceSettings settings, Instant now) {
        MaintenanceSchedule schedule = settings.schedule();
        return new MaintenanceState(
                schedule.enabled() ? schedule.activeAt(now) : settings.enabled(),
                schedule.enabled(), schedule.phaseAt(now), schedule.nextTransitionAt(now));
    }

    boolean shouldKickAfter(MaintenanceState before, boolean beforeKickOnline, boolean kickOnline) {
        return active && kickOnline && (!before.active || !beforeKickOnline);
    }
}
