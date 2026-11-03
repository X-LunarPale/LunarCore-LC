package com.lunarboreal.essentials.feature;

import com.lunarboreal.essentials.EssentialsModule;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 物品价格表 worth.yml。 */
public final class WorthManager {

    private final EssentialsModule m;
    private final Map<Material, Double> prices = new TreeMap<>();
    private File file;

    public WorthManager(EssentialsModule m) {
        this.m = m;
    }

    public void load() {
        file = new File(m.dataFolder(), "worth.yml");
        m.saveResource("worth.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        prices.clear();
        ConfigurationSection s = cfg.getConfigurationSection("worth");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            Material mat = Material.matchMaterial(k);
            if (mat == null) {
                m.logger().warning("worth.yml 未知物品: " + k);
                continue;
            }
            double v = s.getDouble(k);
            if (v > 0) prices.put(mat, v);
        }
    }

    private String serialize() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().setHeader(List.of("物品单价表（/worth 查询、/sell 出售）。键为物品 ID，值为单个物品价格。"));
        for (var e : prices.entrySet()) cfg.set("worth." + e.getKey().name(), e.getValue());
        return cfg.saveToString();
    }

    public void saveAsync() {
        String data = serialize();
        File f = file;
        m.runAsync(() -> write(f, data));
    }

    public void saveBlocking() {
        if (file != null) write(file, serialize());
    }

    private synchronized void write(File f, String data) {
        try {
            Files.writeString(f.toPath(), data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            m.logger().warning("保存 worth.yml 失败: " + e.getMessage());
        }
    }

    public Map<Material, Double> all() {
        return prices;
    }

    public Double price(Material mat) {
        return prices.get(mat);
    }

    public void set(Material mat, double price) {
        if (price <= 0) prices.remove(mat);
        else prices.put(mat, price);
        saveAsync();
    }

    /** 可出售的单价；带自定义名称/附魔/损耗的物品按配置决定是否可售。 */
    public Double sellPrice(ItemStack is) {
        if (is == null || is.getType().isAir()) return null;
        Double p = prices.get(is.getType());
        if (p == null) return null;
        if (!m.config().getBoolean("worth.sell-modified-items", false) && is.hasItemMeta()) {
            ItemMeta meta = is.getItemMeta();
            if (meta.hasDisplayName() || meta.hasEnchants() || meta.hasLore()) return null;
            if (meta instanceof org.bukkit.inventory.meta.Damageable dm && dm.hasDamage() && dm.getDamage() > 0) return null;
        }
        return p;
    }
}
