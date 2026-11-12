package com.lunarboreal.land;

import com.lunarboreal.api.service.LandService.Flag;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 配置快照（每次重载重新构建，避免热点路径反复读 YAML）。 */
public final class Settings {

    /** 仅对玩家生效、可在成员范围设置的 flag。 */
    public static final Set<Flag> PLAYER_FLAGS = Set.of(Flag.BUILD, Flag.BREAK, Flag.INTERACT, Flag.CONTAINER,
            Flag.USE_DOOR, Flag.ANIMAL_DAMAGE, Flag.ENTER, Flag.TELEPORT);

    public final Material tool;
    public final int selectionShowSeconds;
    public final int showSeconds;
    public final Pattern namePattern;

    public final int minSide, maxSide, minArea, maxLands, spawnDistance;
    public final long maxTotalArea;
    public final Set<String> worldBlacklist;

    public final BigDecimal pricePerBlock;
    public final Map<String, BigDecimal> worldPrices;
    public final boolean noEconomyFree;
    final boolean volumePricing, adminDefaultBypass, transitionParticles;
    final int transitionSeconds;
    final long transitionCooldown;
    public final double refundRatio;
    public final double saleTax;

    public final boolean taxEnabled;
    public final BigDecimal taxPerBlock;
    public final boolean taxFreeze;
    public final int taxDeleteAfterDays;

    public final String messageDisplay;
    public final boolean showDefaultMessages;

    public final boolean protectLiquid, protectPistons, protectMobGrief;
    public final long denyCooldown;

    public final Map<Flag, Boolean> defaultGlobal;
    public final Map<Flag, Boolean> defaultMember;

    public Settings(YamlConfiguration c) {
        Material m = Material.matchMaterial(c.getString("selection-tool", "GOLDEN_HOE"));
        tool = m == null ? Material.GOLDEN_HOE : m;
        selectionShowSeconds = c.getInt("selection-show-seconds", 30);
        showSeconds = c.getInt("show-seconds", 20);
        Pattern p;
        try {
            p = Pattern.compile(c.getString("name-pattern", "^[\\p{L}\\p{N}_-]{1,16}$"));
        } catch (Exception e) {
            p = Pattern.compile("^[\\p{L}\\p{N}_-]{1,16}$");
        }
        namePattern = p;

        minSide = Math.max(1, c.getInt("limits.min-side", 5));
        maxSide = Math.max(minSide, c.getInt("limits.max-side", 256));
        minArea = c.getInt("limits.min-area", 25);
        maxLands = c.getInt("limits.max-lands", 3);
        maxTotalArea = c.getLong("limits.max-total-area", 20000);
        spawnDistance = c.getInt("limits.spawn-distance", 64);
        worldBlacklist = new HashSet<>(c.getStringList("limits.world-blacklist"));

        pricePerBlock = dec(c.getString("economy.price-per-block", "1.0"));
        worldPrices = new HashMap<>();
        ConfigurationSection wp = c.getConfigurationSection("economy.world-prices");
        if (wp != null) for (String k : wp.getKeys(false)) worldPrices.put(k, dec(wp.getString(k, "0")));
        volumePricing = "volume".equalsIgnoreCase(c.getString("economy.pricing-mode", "area"));
        adminDefaultBypass = c.getBoolean("admin.default-bypass", true);
        transitionParticles = c.getBoolean("border.transitions.enabled", true);
        transitionSeconds = Math.max(1, Math.min(120, c.getInt("border.transitions.duration-seconds", 5)));
        transitionCooldown = Math.max(0, Math.min(300_000, c.getLong("border.transitions.cooldown-ms", 3000)));
        noEconomyFree = !"deny".equalsIgnoreCase(c.getString("economy.no-economy", "free"));
        refundRatio = clamp(c.getDouble("economy.refund-ratio", 0.5));
        saleTax = clamp(c.getDouble("economy.sale-tax", 0.05));

        taxEnabled = c.getBoolean("tax.enabled", false);
        taxPerBlock = dec(c.getString("tax.per-block-per-day", "0.01"));
        taxFreeze = c.getBoolean("tax.freeze", true);
        taxDeleteAfterDays = c.getInt("tax.delete-after-days", 0);

        messageDisplay = c.getString("messages.display", "actionbar").toLowerCase(Locale.ROOT);
        showDefaultMessages = c.getBoolean("messages.show-default", true);

        protectLiquid = c.getBoolean("protection.liquid-flow", true);
        protectPistons = c.getBoolean("protection.pistons", true);
        protectMobGrief = c.getBoolean("protection.mob-grief", true);
        denyCooldown = c.getLong("protection.deny-message-cooldown", 1500);

        defaultGlobal = readFlags(c.getConfigurationSection("default-flags.global"), Set.of(Flag.values()));
        defaultMember = readFlags(c.getConfigurationSection("default-flags.member"), PLAYER_FLAGS);
        // 缺省值兜底
        for (Flag f : Flag.values()) defaultGlobal.putIfAbsent(f, f == Flag.ENTER || f == Flag.TELEPORT || f == Flag.MOB_SPAWN);
        for (Flag f : PLAYER_FLAGS) defaultMember.putIfAbsent(f, true);
    }

    public BigDecimal priceIn(String world) {
        return worldPrices.getOrDefault(world, pricePerBlock);
    }

    private static Map<Flag, Boolean> readFlags(ConfigurationSection sec, Set<Flag> allowed) {
        Map<Flag, Boolean> out = new EnumMap<>(Flag.class);
        if (sec == null) return out;
        for (String k : sec.getKeys(false)) {
            try {
                Flag f = Flag.valueOf(k.toUpperCase(Locale.ROOT));
                if (allowed.contains(f)) out.put(f, sec.getBoolean(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    static BigDecimal dec(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static double clamp(double d) {
        return Math.max(0, Math.min(1, d));
    }
}
