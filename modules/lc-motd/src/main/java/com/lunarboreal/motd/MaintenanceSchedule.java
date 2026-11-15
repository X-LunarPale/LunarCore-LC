package com.lunarboreal.motd;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/** An optional, absolute time window; boundaries are parsed once when settings are loaded. */
final class MaintenanceSchedule {
    private final boolean enabled;
    private final String startAt;
    private final String endAt;
    private final Instant start;
    private final Instant end;

    MaintenanceSchedule(boolean enabled, String startAt, String endAt) {
        this.enabled = enabled;
        this.startAt = normalize(startAt, "维护开始时间");
        this.endAt = normalize(endAt, "维护结束时间");
        start = this.startAt.isEmpty() ? null : OffsetDateTime.parse(this.startAt).toInstant();
        end = this.endAt.isEmpty() ? null : OffsetDateTime.parse(this.endAt).toInstant();
        if (enabled && start == null) throw new IllegalArgumentException("请选择维护开始时间");
        if (end != null && start == null) throw new IllegalArgumentException("设置结束时间前，请先选择开始时间");
        if (start != null && end != null && !start.isBefore(end))
            throw new IllegalArgumentException("维护结束时间必须晚于开始时间");
    }

    static MaintenanceSchedule defaults() { return new MaintenanceSchedule(false, "", ""); }

    static MaintenanceSchedule load(ConfigurationSection config) {
        if (config.contains("maintenance.schedule") && !config.isConfigurationSection("maintenance.schedule"))
            throw new IllegalArgumentException("定时维护配置须为配置节");
        ConfigurationSection section = config.getConfigurationSection("maintenance.schedule");
        if (section == null) return defaults();
        if (!(section.get("enabled", false) instanceof Boolean enabled))
            throw new IllegalArgumentException("定时维护开关须为布尔值");
        if (!(section.get("start-at", "") instanceof String start)
                || !(section.get("end-at", "") instanceof String end))
            throw new IllegalArgumentException("定时维护时间须为文本");
        return new MaintenanceSchedule(enabled, start, end);
    }

    boolean enabled() { return enabled; }
    String startAt() { return startAt; }
    String endAt() { return endAt; }

    boolean activeAt(Instant now) {
        return enabled && start != null && !now.isBefore(start) && (end == null || now.isBefore(end));
    }

    String phaseAt(Instant now) {
        if (!enabled) return "disabled";
        if (now.isBefore(start)) return "pending";
        return end != null && !now.isBefore(end) ? "completed" : "active";
    }

    String nextTransitionAt(Instant now) {
        return switch (phaseAt(now)) {
            case "pending" -> startAt;
            case "active" -> endAt;
            default -> "";
        };
    }

    Map<String, Object> webValues() {
        return Map.of("scheduleEnabled", enabled, "scheduleStart", startAt, "scheduleEnd", endAt);
    }

    void apply(ConfigurationSection config) {
        config.set("maintenance.schedule.enabled", enabled);
        config.set("maintenance.schedule.start-at", startAt);
        config.set("maintenance.schedule.end-at", endAt);
    }

    private static String normalize(String value, String label) {
        if (value == null) throw new IllegalArgumentException(label + "不得为空");
        String text = value.strip();
        if (text.isEmpty()) return "";
        try {
            if (text.length() > 64) throw new DateTimeException("timestamp too long");
            return OffsetDateTime.parse(text).toString();
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException(label + "无效，请提供包含时区的日期和时间");
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof MaintenanceSchedule that && enabled == that.enabled
                && startAt.equals(that.startAt) && endAt.equals(that.endAt);
    }

    @Override public int hashCode() { return Objects.hash(enabled, startAt, endAt); }
}
