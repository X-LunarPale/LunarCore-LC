package com.lunarboreal.signin;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/** config.yml + rewards.yml 的不可变快照。 */
public final class SigninSettings {

    public final ZoneId zone;
    public final boolean guiEnabled;
    public final boolean broadcast;
    public final boolean remindJoin;
    public final int remindDelay;
    public final boolean remindNewDay;
    public final boolean makeupEnabled;
    public final BigDecimal makeupCost;
    public final int makeupLimit;
    public final boolean makeupDaily;
    public final boolean makeupMilestones;
    public final String textSigned;
    public final String textUnsigned;

    public final Material matSigned, matMakeup, matToday, matMissed, matLocked, matFuture, matInfo, matRewards, matClose, matFiller;

    public final Reward daily;
    public final Map<Integer, Reward> streak;
    public final int streakCycle;
    public final Map<Integer, Reward> milestones;

    public SigninSettings(YamlConfiguration c, YamlConfiguration rewards, Logger log) {
        ZoneId z;
        try {
            z = ZoneId.of(c.getString("timezone", "Asia/Shanghai"));
        } catch (DateTimeException e) {
            log.warning("时区无效，使用 Asia/Shanghai: " + c.getString("timezone"));
            z = ZoneId.of("Asia/Shanghai");
        }
        zone = z;
        guiEnabled = c.getBoolean("gui-enabled", true);
        broadcast = c.getBoolean("broadcast", false);
        remindJoin = c.getBoolean("reminder.on-join", true);
        remindDelay = Math.max(0, c.getInt("reminder.delay-seconds", 3));
        remindNewDay = c.getBoolean("reminder.on-new-day", true);
        makeupEnabled = c.getBoolean("makeup.enabled", true);
        BigDecimal cost;
        try {
            cost = new BigDecimal(String.valueOf(c.get("makeup.cost", 0)).trim());
        } catch (NumberFormatException e) {
            cost = BigDecimal.ZERO;
        }
        makeupCost = cost.max(BigDecimal.ZERO);
        makeupLimit = Math.max(0, c.getInt("makeup.monthly-limit", 3));
        makeupDaily = c.getBoolean("makeup.give-daily-reward", true);
        makeupMilestones = c.getBoolean("makeup.check-milestones", true);
        textSigned = c.getString("placeholder.signed", "已签到");
        textUnsigned = c.getString("placeholder.unsigned", "未签到");

        matSigned = mat(c, "gui.signed", Material.LIME_STAINED_GLASS_PANE);
        matMakeup = mat(c, "gui.makeup-signed", Material.YELLOW_STAINED_GLASS_PANE);
        matToday = mat(c, "gui.today", Material.SUNFLOWER);
        matMissed = mat(c, "gui.missed", Material.ORANGE_STAINED_GLASS_PANE);
        matLocked = mat(c, "gui.missed-locked", Material.RED_STAINED_GLASS_PANE);
        matFuture = mat(c, "gui.future", Material.GRAY_STAINED_GLASS_PANE);
        matInfo = mat(c, "gui.info", Material.CLOCK);
        matRewards = mat(c, "gui.rewards", Material.BOOK);
        matClose = mat(c, "gui.close", Material.BARRIER);
        matFiller = mat(c, "gui.filler", Material.BLACK_STAINED_GLASS_PANE);

        daily = Reward.parse(rewards.getConfigurationSection("rewards.daily"), log);
        streak = numbered(rewards.getConfigurationSection("rewards.streak"), log);
        streakCycle = Math.max(0, rewards.getInt("rewards.streak-cycle", 0));
        milestones = numbered(rewards.getConfigurationSection("rewards.milestones"), log);
    }

    private static Material mat(YamlConfiguration c, String path, Material def) {
        Material m = Material.matchMaterial(c.getString(path, def.name()));
        return m == null || !m.isItem() ? def : m;
    }

    private static Map<Integer, Reward> numbered(ConfigurationSection sec, Logger log) {
        if (sec == null) return Collections.emptyMap();
        Map<Integer, Reward> out = new TreeMap<>();
        for (String k : sec.getKeys(false)) {
            try {
                int n = Integer.parseInt(k.trim());
                if (n > 0) out.put(n, Reward.parse(sec.getConfigurationSection(k), log));
            } catch (NumberFormatException e) {
                log.warning("签到奖励键必须是正整数: " + k);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** 连续天数映射到奖励表中的天数（考虑循环周期）。 */
    public int streakKey(int streak) {
        if (streakCycle > 0 && streak > streakCycle) return ((streak - 1) % streakCycle) + 1;
        return streak;
    }
}
