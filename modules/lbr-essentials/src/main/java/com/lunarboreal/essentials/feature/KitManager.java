package com.lunarboreal.essentials.feature;

import com.lunarboreal.api.util.Durations;
import com.lunarboreal.essentials.EssentialsModule;
import com.lunarboreal.essentials.data.UserData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 礼包系统，数据保存在 kits.yml。 */
public final class KitManager {

    public static final class Kit {
        public final String name;
        public List<ItemStack> items = new ArrayList<>();
        /** 冷却毫秒，0 为无冷却 */
        public long cooldown;
        /** 一次性礼包（每人只能领一次） */
        public boolean once;
        /** 首次进服自动发放 */
        public boolean firstJoin;
        /** 是否需要 lbr.essentials.kits.<name> 权限 */
        public boolean requirePermission = true;
        public Material icon = Material.CHEST;
        public String display;

        public Kit(String name) {
            this.name = name;
        }

        public String permission() {
            return "lbr.essentials.kits." + name;
        }
    }

    public enum ClaimResult { OK, NO_PERMISSION, COOLDOWN, ALREADY_CLAIMED, NOT_FOUND, LOADING }

    private final EssentialsModule m;
    private final Map<String, Kit> kits = new TreeMap<>();
    private File file;

    public KitManager(EssentialsModule m) {
        this.m = m;
    }

    public void load() {
        file = new File(m.dataFolder(), "kits.yml");
        m.saveResource("kits.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        kits.clear();
        ConfigurationSection root = cfg.getConfigurationSection("kits");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            Kit k = new Kit(key.toLowerCase(Locale.ROOT));
            String cd = s.getString("cooldown", "0");
            try {
                Long ms = cd == null || cd.equals("0") ? Long.valueOf(0L) : Durations.parse(cd);
                k.cooldown = ms == null ? 0 : ms;
            } catch (IllegalArgumentException e) {
                m.logger().warning("礼包 " + key + " 冷却格式错误: " + cd);
            }
            k.once = s.getBoolean("once", false);
            k.firstJoin = s.getBoolean("first-join", false);
            k.requirePermission = s.getBoolean("require-permission", true);
            k.display = s.getString("display", key);
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            if (icon != null && icon.isItem()) k.icon = icon;
            List<?> list = s.getList("items", List.of());
            for (Object o : list) if (o instanceof ItemStack is) k.items.add(is);
            for (String simple : s.getStringList("simple-items")) {
                String[] parts = simple.split(":");
                String matName = parts.length > 2 || (parts.length == 2 && !parts[1].matches("\\d+")) ? parts[0] + ":" + parts[1] : parts[0];
                String amt = parts.length >= 2 && parts[parts.length - 1].matches("\\d+") ? parts[parts.length - 1] : "1";
                Material mat = Material.matchMaterial(matName);
                if (mat == null || !mat.isItem()) {
                    m.logger().warning("礼包 " + key + " 未知物品: " + simple);
                    continue;
                }
                k.items.add(new ItemStack(mat, Math.max(1, Integer.parseInt(amt))));
            }
            kits.put(k.name, k);
        }
    }

    private YamlConfiguration toYaml() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().setHeader(List.of("礼包配置。可用 /createkit 从背包创建，也可在网页面板管理。",
                "cooldown: 冷却（如 1d、12h、30m，0 为无冷却）；once: 每人限领一次；first-join: 首次进服自动发放；",
                "require-permission: 是否需要权限 lbr.essentials.kits.<名称>；icon: GUI 图标；items: 物品（由插件序列化）"));
        for (Kit k : kits.values()) {
            String p = "kits." + k.name + ".";
            cfg.set(p + "display", k.display);
            cfg.set(p + "cooldown", k.cooldown <= 0 ? "0" : (k.cooldown / 1000) + "s");
            cfg.set(p + "once", k.once);
            cfg.set(p + "first-join", k.firstJoin);
            cfg.set(p + "require-permission", k.requirePermission);
            cfg.set(p + "icon", k.icon.name());
            cfg.set(p + "items", new ArrayList<>(k.items));
        }
        return cfg;
    }

    /** 主线程序列化，异步写盘。 */
    public void saveAsync() {
        String data = toYaml().saveToString();
        File f = file;
        m.runAsync(() -> write(f, data));
    }

    public void saveBlocking() {
        if (file == null) return;
        write(file, toYaml().saveToString());
    }

    private synchronized void write(File f, String data) {
        try {
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            m.logger().warning("保存 kits.yml 失败: " + e.getMessage());
        }
    }

    public Collection<Kit> all() {
        return kits.values();
    }

    public Kit get(String name) {
        return name == null ? null : kits.get(name.toLowerCase(Locale.ROOT));
    }

    public void put(Kit k) {
        kits.put(k.name, k);
        saveAsync();
    }

    public boolean remove(String name) {
        boolean r = kits.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (r) saveAsync();
        return r;
    }

    public boolean canUse(Player p, Kit k) {
        return !k.requirePermission || p.hasPermission(k.permission()) || p.hasPermission("lbr.essentials.kits.*");
    }

    /** 剩余冷却毫秒，0 表示可领。 */
    public long remaining(Player p, Kit k) {
        if (k.cooldown <= 0 || p.hasPermission("lbr.essentials.kit.bypasscooldown")) return 0;
        Long last = m.data(p).kitUses.get(k.name);
        if (last == null) return 0;
        return Math.max(0, last + k.cooldown - System.currentTimeMillis());
    }

    /**
     * 发放礼包。
     * @param check 是否检查权限/冷却（管理员给他人发放时为 false）
     */
    public ClaimResult give(Player p, Kit k, boolean check) {
        UserData d = m.data(p);
        if (check) {
            if (!d.loaded) return ClaimResult.LOADING;
            if (!canUse(p, k)) return ClaimResult.NO_PERMISSION;
            if (k.once && d.kitOnce.contains(k.name)) return ClaimResult.ALREADY_CLAIMED;
            if (remaining(p, k) > 0) return ClaimResult.COOLDOWN;
        }
        HashMap<Integer, ItemStack> left = p.getInventory().addItem(k.items.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        for (ItemStack is : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), is);
        if (check) {
            d.kitUses.put(k.name, System.currentTimeMillis());
            if (k.once) d.kitOnce.add(k.name);
            d.markDirty();
        }
        return ClaimResult.OK;
    }

    /** 首次进服发放。 */
    public void giveFirstJoin(Player p) {
        UserData d = m.data(p);
        if (!d.loaded || d.firstKitsGiven) return;
        d.firstKitsGiven = true;
        d.markDirty();
        for (Kit k : kits.values()) {
            if (!k.firstJoin) continue;
            give(p, k, false);
            if (k.once) d.kitOnce.add(k.name);
            d.kitUses.put(k.name, System.currentTimeMillis());
            m.msg(p, "kit.received", "kit", com.lunarboreal.api.message.MessageService.rich(k.display == null ? k.name : k.display));
        }
    }

    public static boolean validName(String n) {
        return n.matches("[a-zA-Z0-9_\\-]{1,32}");
    }

    public static Material iconOr(Material m) {
        return m == null || !m.isItem() || m.isAir() ? Material.CHEST : m;
    }
}
