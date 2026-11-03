package com.lunarboreal.antixray;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读写 Paper 自带 anti-xray 配置（方案一：混淆）。
 * <ul>
 *   <li>全局默认：config/paper-world-defaults.yml</li>
 *   <li>单世界覆盖：&lt;世界目录&gt;/paper-world.yml</li>
 * </ul>
 * 节点：anticheat.anti-xray.{enabled, engine-mode, max-block-height, update-radius, lava-obscures, use-permission, hidden-blocks, replacement-blocks}。
 * Paper 只在启动时读取这些文件，修改后需要重启服务器才会生效。
 * 文件读写均为阻塞 IO，调用方需在异步线程执行。
 */
final class PaperXrayConfig {

    static final String DEFAULTS = "defaults";
    static final String ROOT = "anticheat.anti-xray";
    static final List<String> SCALAR_KEYS = List.of("enabled", "engine-mode", "max-block-height", "update-radius", "lava-obscures", "use-permission");

    private static final List<String> OVERWORLD_ORES = List.of(
            "copper_ore", "deepslate_copper_ore", "raw_copper_block", "gold_ore", "deepslate_gold_ore",
            "iron_ore", "deepslate_iron_ore", "raw_iron_block", "coal_ore", "deepslate_coal_ore",
            "lapis_ore", "deepslate_lapis_ore", "mossy_cobblestone", "obsidian", "chest",
            "diamond_ore", "deepslate_diamond_ore", "redstone_ore", "deepslate_redstone_ore",
            "clay", "emerald_ore", "deepslate_emerald_ore", "ender_chest");

    /** 预设：id → 显示名 + 要写入的值。 */
    record Preset(String id, String label, Map<String, Object> values) {}

    static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    static {
        Map<String, Object> ow2 = new LinkedHashMap<>();
        ow2.put("enabled", true);
        ow2.put("engine-mode", 2);
        ow2.put("max-block-height", 64);
        ow2.put("update-radius", 2);
        ow2.put("lava-obscures", false);
        ow2.put("hidden-blocks", OVERWORLD_ORES);
        ow2.put("replacement-blocks", List.of("stone", "oak_planks", "deepslate"));
        PRESETS.put("overworld-2", new Preset("overworld-2", "主世界 · 引擎 2（随机方块，推荐）", ow2));

        Map<String, Object> ow3 = new LinkedHashMap<>();
        ow3.put("enabled", true);
        ow3.put("engine-mode", 3);
        ow3.put("max-block-height", 64);
        ow3.put("update-radius", 2);
        ow3.put("lava-obscures", false);
        ow3.put("hidden-blocks", List.of("air", "copper_ore", "deepslate_copper_ore", "raw_copper_block",
                "diamond_ore", "deepslate_diamond_ore", "gold_ore", "deepslate_gold_ore", "iron_ore",
                "deepslate_iron_ore", "raw_iron_block", "lapis_ore", "deepslate_lapis_ore", "redstone_ore",
                "deepslate_redstone_ore"));
        ow3.put("replacement-blocks", List.of("chest", "amethyst_block", "andesite", "budding_amethyst", "calcite",
                "coal_ore", "deepslate_coal_ore", "deepslate", "diorite", "dirt", "emerald_ore",
                "deepslate_emerald_ore", "granite", "gravel", "oak_planks", "smooth_basalt", "stone", "tuff"));
        PRESETS.put("overworld-3", new Preset("overworld-3", "主世界 · 引擎 3（分层混淆，最强但更耗流量）", ow3));

        Map<String, Object> ow1 = new LinkedHashMap<>();
        ow1.put("enabled", true);
        ow1.put("engine-mode", 1);
        ow1.put("max-block-height", 64);
        ow1.put("update-radius", 2);
        ow1.put("hidden-blocks", OVERWORLD_ORES);
        ow1.put("replacement-blocks", List.of("stone", "deepslate"));
        PRESETS.put("overworld-1", new Preset("overworld-1", "主世界 · 引擎 1（替换为石头，最轻量）", ow1));

        Map<String, Object> n2 = new LinkedHashMap<>();
        n2.put("enabled", true);
        n2.put("engine-mode", 2);
        n2.put("max-block-height", 128);
        n2.put("update-radius", 2);
        n2.put("lava-obscures", false);
        n2.put("hidden-blocks", List.of("ancient_debris", "nether_gold_ore", "nether_quartz_ore"));
        n2.put("replacement-blocks", List.of("basalt", "blackstone", "gravel", "netherrack", "soul_sand", "soul_soil"));
        PRESETS.put("nether-2", new Preset("nether-2", "下界 · 引擎 2", n2));

        Map<String, Object> n3 = new LinkedHashMap<>(n2);
        n3.put("engine-mode", 3);
        n3.put("hidden-blocks", List.of("air", "ancient_debris", "bone_block", "glowstone", "magma_block",
                "nether_bricks", "nether_gold_ore", "nether_quartz_ore", "polished_blackstone_bricks"));
        PRESETS.put("nether-3", new Preset("nether-3", "下界 · 引擎 3", n3));

        Map<String, Object> off = new LinkedHashMap<>();
        off.put("enabled", false);
        PRESETS.put("disable", new Preset("disable", "关闭 anti-xray", off));
    }

    private PaperXrayConfig() {}

    static File defaultsFile() {
        return new File("config", "paper-world-defaults.yml");
    }

    /** 世界的 paper-world.yml；target 为 "defaults" 时返回全局默认文件。 */
    static File fileFor(String target) {
        if (DEFAULTS.equalsIgnoreCase(target)) return defaultsFile();
        World w = Bukkit.getWorld(target);
        File folder = w != null ? w.getWorldFolder() : new File(Bukkit.getWorldContainer(), target);
        return new File(folder, "paper-world.yml");
    }

    static List<String> targets() {
        List<String> out = new ArrayList<>();
        out.add(DEFAULTS);
        for (World w : Bukkit.getWorlds()) out.add(w.getName());
        return out;
    }

    static YamlConfiguration load(File file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        if (file.exists()) {
            try {
                y.load(file);
            } catch (InvalidConfigurationException e) {
                throw new IOException("YAML 格式错误: " + e.getMessage(), e);
            }
        }
        return y;
    }

    /**
     * 读取某个目标的 anti-xray 设置。返回的 map 中 "inherited" 列出了未在世界文件中覆盖、沿用默认值的键。
     */
    static Map<String, Object> read(String target) throws IOException {
        File file = fileFor(target);
        YamlConfiguration y = load(file);
        YamlConfiguration def = DEFAULTS.equalsIgnoreCase(target) ? y : load(defaultsFile());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", target);
        out.put("file", file.getPath());
        out.put("exists", file.exists());
        List<String> inherited = new ArrayList<>();
        for (String k : SCALAR_KEYS) {
            String path = ROOT + "." + k;
            Object v = y.get(path);
            if ((v == null || "default".equals(String.valueOf(v))) && !DEFAULTS.equalsIgnoreCase(target)) {
                v = def.get(path);
                inherited.add(k);
            }
            out.put(k, v);
        }
        for (String k : List.of("hidden-blocks", "replacement-blocks")) {
            String path = ROOT + "." + k;
            List<String> list = y.isList(path) ? y.getStringList(path) : null;
            if (list == null && !DEFAULTS.equalsIgnoreCase(target)) {
                list = def.getStringList(path);
                inherited.add(k);
            }
            out.put(k, list == null ? List.of() : list);
        }
        out.put("inherited", inherited);
        return out;
    }

    /** 写入若干键（值为 null 表示删除覆盖，恢复继承默认）。 */
    static void write(String target, Map<String, Object> values) throws IOException {
        File file = fileFor(target);
        if (!DEFAULTS.equalsIgnoreCase(target) && Bukkit.getWorld(target) == null && !file.getParentFile().exists()) {
            throw new IOException("世界不存在: " + target);
        }
        YamlConfiguration y = load(file);
        for (Map.Entry<String, Object> e : values.entrySet()) {
            y.set(ROOT + "." + e.getKey(), e.getValue());
        }
        file.getParentFile().mkdirs();
        y.save(file);
    }

    static void applyPreset(String target, String presetId) throws IOException {
        Preset p = PRESETS.get(presetId);
        if (p == null) throw new IOException("未知预设: " + presetId);
        write(target, p.values());
    }
}
