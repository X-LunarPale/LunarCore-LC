package com.lunarboreal.terrain;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.terrain.nms.NativeTectonicBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/** World registrations, queues and Bukkit operations are confined to the server thread. */
public final class TerrainModule extends LbrModule {
    private final Map<String, TerrainWorldSpec> worlds = new LinkedHashMap<>();
    private final Map<String, String> states = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private final ArrayDeque<LoadRequest> queue = new ArrayDeque<>();
    private final Set<String> established = new HashSet<>();
    private NativeTectonicBridge bridge;
    private TerrainCommands commands;
    private Path registrations;
    private boolean storageReady;
    private boolean supported;
    private boolean working;
    private String initializationError = "";

    private record LoadRequest(String name, String actor) {}

    @Override public void onEnable() {
        registrations = dataFolder().toPath().resolve("worlds.yml");
        try {
            readRegistrations();
            storageReady = true;
        } catch (Exception e) {
            initializationError = "无法读取地形世界登记，写入已禁用：" + message(e);
            logger().log(Level.SEVERE, initializationError, e);
        }
        if (api().isPaper() && Bukkit.getMinecraftVersion().equals("26.3")) {
            try {
                bridge = new NativeTectonicBridge();
                var heights = new java.util.LinkedHashSet<>(TerrainHeight.DEFAULTS);
                worlds.values().forEach(spec -> heights.add(spec.options().heightRange()));
                config().getMapList("height-ranges").forEach(row -> heights.add(TerrainHeight.fromConfig(row)));
                bridge.initialize(heights);
                registerListener(bridge);
                supported = true;
            } catch (Throwable e) {
                initializationError = "原生地形生成器初始化失败：" + message(e);
                logger().log(Level.SEVERE, initializationError, e);
                closeBridge();
            }
        } else {
            initializationError = "原生地形生成器需要 Paper 26.3";
        }
        new TerrainWeb(this).register();
        commands = new TerrainCommands(this);
        registerCommand(commands);
        placeholder((player, key) -> switch (key) {
            case "worlds" -> Integer.toString(worlds.size());
            case "ready" -> Boolean.toString(supported && storageReady);
            default -> null;
        });
        // STARTUP modules run before the primary world has been initialized.
        runTask(() -> {
            if (!supported || !storageReady) return;
            boolean autoLoad = config().getBoolean("auto-load", true);
            for (TerrainWorldSpec spec : List.copyOf(worlds.values())) {
                try {
                    if (Bukkit.getWorld(new NamespacedKey("lbr_terrain", spec.name())) != null) {
                        bridge.create(spec.name(), spec.seed(), spec.options(), true);
                        states.put(spec.name(), "ready");
                        errors.put(spec.name(), "");
                    } else if (autoLoad && spec.autoLoad()) {
                        queueLoad(spec.name(), "startup");
                    }
                } catch (Exception | LinkageError e) {
                    fail(spec.name(), e);
                }
            }
        });
    }

    TerrainOptions optionsFor(World world) {
        if (world == null || !world.getKey().getNamespace().equals("lbr_terrain")) return null;
        TerrainWorldSpec spec = worlds.get(world.getKey().getKey());
        return spec == null ? null : spec.options();
    }

    @Override public void onDisable() {
        if (commands != null) commands.close();
        queue.clear();
        closeBridge();
        if (storageReady) {
            try {
                persist();
            } catch (IOException e) {
                logger().log(Level.SEVERE, "无法保存地形世界登记", e);
            }
        }
    }

    private void closeBridge() {
        if (bridge == null) return;
        try {
            bridge.close();
        } catch (Exception | LinkageError e) {
            logger().log(Level.WARNING, "无法卸载原生地形运行设置", e);
        }
        supported = false;
    }

    @Override public void onReload() {
        reloadConfig();
        // Generation parameters belong to the saved world and remain immutable.
    }

    private void readRegistrations() throws Exception {
        if (!Files.exists(registrations)) return;
        if (Files.isSymbolicLink(registrations)) throw new IOException("登记文件不能是符号链接");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(registrations.toFile());
        ConfigurationSection section = yaml.getConfigurationSection("worlds");
        if (section == null) {
            if (yaml.contains("worlds")) throw new IOException("worlds 必须为配置对象");
            return;
        }
        for (String name : section.getKeys(false)) {
            TerrainWorldSpec.validateName(name);
            ConfigurationSection row = section.getConfigurationSection(name);
            if (row == null || !row.isString("seed")) throw new IOException("世界种子登记无效：" + name);
            String seed = row.getString("seed", "");
            if (seed.isBlank()) throw new IOException("世界种子缺失：" + name);
            ConfigurationSection options = row.getConfigurationSection("config");
            if (options == null) throw new IOException("世界生成参数缺失：" + name);
            TerrainWorldSpec spec = new TerrainWorldSpec(name, TerrainWorldSpec.parseSeed(seed),
                    readOptions(options), row.getBoolean("autoLoad", true));
            worlds.put(name, spec);
            if (row.getBoolean("created", false)) established.add(name);
            states.put(name, "registered");
            errors.put(name, "");
        }
    }

    static TerrainOptions readOptions(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>(section.getValues(false));
        ConfigurationSection advanced = section.getConfigurationSection("advanced");
        if (advanced != null) {
            Map<String, Object> flattened = new LinkedHashMap<>();
            advanced.getValues(true).forEach((key, value) -> {
                if (!(value instanceof ConfigurationSection)) flattened.put(key, value);
            });
            values.put("advanced", flattened);
        }
        return TerrainOptions.parse(values);
    }

    private void persist() throws IOException {
        if (!storageReady) throw new IOException("地形世界登记不可写，请检查启动日志");
        YamlConfiguration yaml = new YamlConfiguration();
        for (TerrainWorldSpec spec : worlds.values()) {
            String prefix = "worlds." + spec.name() + ".";
            yaml.set(prefix + "seed", Long.toString(spec.seed()));
            yaml.set(prefix + "config", spec.options().map());
            yaml.set(prefix + "autoLoad", spec.autoLoad());
            yaml.set(prefix + "created", established.contains(spec.name()));
        }
        Path temporary = Files.createTempFile(dataFolder().toPath(), "worlds-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString());
            try {
                Files.move(temporary, registrations, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, registrations, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void requireWritable() {
        if (!supported || !storageReady) throw new IllegalArgumentException(initializationError);
        if (!isEnabled()) throw new IllegalArgumentException("地形模块已禁用");
    }

    Map<String, Object> status() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TerrainWorldSpec spec : worlds.values()) {
            World world = Bukkit.getWorld(new NamespacedKey("lbr_terrain", spec.name()));
            Map<String, Object> row = new LinkedHashMap<>(spec.map());
            row.put("key", spec.key());
            row.put("loaded", world != null);
            row.put("players", world == null ? 0 : world.getPlayers().size());
            row.put("minY", world == null ? spec.options().minY() : world.getMinHeight());
            row.put("maxY", world == null ? spec.options().maxY() : world.getMaxHeight());
            if (world != null) {
                var spawn = world.getSpawnLocation();
                row.put("spawn", Map.of("x", spawn.getBlockX(), "y", spawn.getBlockY(), "z", spawn.getBlockZ()));
            }
            row.put("status", states.getOrDefault(spec.name(), "registered"));
            row.put("error", errors.getOrDefault(spec.name(), ""));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", supported && storageReady);
        result.put("gameVersion", Bukkit.getMinecraftVersion());
        result.put("upstream", Map.of("name", "Tectonic", "version", "3.0.29", "license", "MIT"));
        result.put("defaults", TerrainOptions.defaults().map());
        result.put("schema", TerrainOptions.schema());
        result.put("heights", supported ? bridge.heights() : List.of());
        result.put("heightRanges", supported ? bridge.heightRanges() : List.of());
        result.put("presets", TerrainPresets.all());
        result.put("worlds", rows);
        result.put("busy", working || !queue.isEmpty());
        result.put("error", initializationError);
        return result;
    }

    void addHeightRange(Map<String, Object> body, String actor) throws IOException {
        requireWritable();
        TerrainHeight height = TerrainHeight.fromConfig(body);
        List<Map<?, ?>> ranges = new ArrayList<>(config().getMapList("height-ranges"));
        if (ranges.stream().map(TerrainHeight::fromConfig).noneMatch(height::equals)) {
            ranges.add(Map.of("min-y", height.minY(), "max-y", height.maxY()));
            config().set("height-ranges", ranges);
            config().save(dataFolder().toPath().resolve("config.yml").toFile());
            audit(actor, "terrain.height", height.minY() + ".." + height.maxY());
        }
    }

    void create(Map<String, Object> body, String actor) throws Exception {
        requireWritable();
        for (String key : body.keySet()) if (!Set.of("name", "seed", "config").contains(key)) {
            throw new IllegalArgumentException("未知创建字段：" + key);
        }
        String name = TerrainWorldSpec.validateName(body.get("name"));
        if (worlds.containsKey(name) || Bukkit.getWorlds().stream().anyMatch(w -> w.getName().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("同名世界已登记或已加载");
        }
        if (!(body.get("config") instanceof Map<?, ?> rawOptions)) throw new IllegalArgumentException("缺少生成参数");
        Map<String, Object> values = new LinkedHashMap<>();
        rawOptions.forEach((k, v) -> {
            if (!(k instanceof String key)) throw new IllegalArgumentException("生成参数键无效");
            values.put(key, v);
        });
        TerrainOptions options = TerrainOptions.parse(values);
        if (!bridge.supports(options)) {
            throw new IllegalArgumentException("此世界高度需要使用更新后的地形模块完整重启服务器");
        }
        long seed = TerrainWorldSpec.parseSeed(body.getOrDefault("seed", ""));
        Path target = TerrainWorldSpec.dimensionPath(Bukkit.getServer().getLevelDirectory(), name);
        Path legacy = Bukkit.getWorldContainer().toPath().toRealPath().resolve(name);
        Path vanillaDimension = Bukkit.getServer().getLevelDirectory().resolve("dimensions/minecraft").resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(vanillaDimension, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("同名存档目录已存在，拒绝覆盖；请换一个新名称");
        }
        TerrainWorldSpec spec = new TerrainWorldSpec(name, seed, options, true);
        worlds.put(name, spec);
        states.put(name, "registered");
        errors.put(name, "");
        try {
            persist();
        } catch (IOException e) {
            worlds.remove(name);
            states.remove(name);
            errors.remove(name);
            throw e;
        }
        audit(actor, "terrain.create", name + " seed=" + seed + " config=" + options.map());
        queueLoad(name, actor);
    }

    private TerrainWorldSpec require(String name) {
        TerrainWorldSpec spec = worlds.get(TerrainWorldSpec.validateName(name));
        if (spec == null) throw new IllegalArgumentException("地形世界未登记");
        return spec;
    }

    void queueLoad(String name, String actor) throws IOException {
        requireWritable();
        TerrainWorldSpec spec = require(name);
        if (Set.of("queued", "running").contains(states.get(name))) throw new IllegalArgumentException("世界操作正在执行");
        if (Bukkit.getWorld(new NamespacedKey("lbr_terrain", name)) != null) throw new IllegalArgumentException("世界已经加载");
        worlds.put(name, spec.withAutoLoad(true));
        try {
            persist();
        } catch (IOException e) {
            worlds.put(name, spec);
            throw e;
        }
        states.put(name, "queued");
        errors.put(name, "");
        queue.addLast(new LoadRequest(name, actor));
        if (!working && queue.size() == 1) runTask(this::next);
    }

    private void next() {
        if (!isEnabled() || working || queue.isEmpty()) return;
        working = true;
        LoadRequest request = queue.removeFirst();
        String name = request.name();
        states.put(name, "running");
        try {
            TerrainWorldSpec spec = require(name);
            Path target = TerrainWorldSpec.dimensionPath(Bukkit.getServer().getLevelDirectory(), name);
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (established.contains(name) && !exists) {
                throw new IOException("已创建世界的存档目录缺失，拒绝重新生成；请恢复备份");
            }
            // Persist the creation intent before touching world storage, including across crashes.
            boolean addedIntent = established.add(name);
            try {
                persist();
            } catch (IOException e) {
                if (addedIntent) established.remove(name);
                throw e;
            }
            World world = bridge.create(name, spec.seed(), spec.options(), exists);
            if (world == null || world.getMinHeight() != spec.options().minY()
                    || world.getMaxHeight() != spec.options().maxY()) {
                throw new IOException("原生世界实际高度不符，已停止后续操作");
            }
            states.put(name, "ready");
            errors.put(name, "");
            world.save();
            audit(request.actor(), "terrain.ready", name + " height=" + spec.options().height());
        } catch (Exception | LinkageError e) {
            fail(name, e);
        } finally {
            working = false;
            if (!queue.isEmpty()) runTask(this::next);
        }
    }

    private void fail(String name, Throwable e) {
        states.put(name, "failed");
        errors.put(name, message(e));
        logger().log(Level.SEVERE, "原生地形世界失败：" + name, e);
        audit("system", "terrain.failed", name + " " + message(e));
    }

    void unload(String name, String actor) throws IOException {
        requireWritable();
        TerrainWorldSpec spec = require(name);
        if (Set.of("queued", "running").contains(states.get(name))) throw new IllegalArgumentException("世界操作正在执行");
        World world = Bukkit.getWorld(new NamespacedKey("lbr_terrain", name));
        if (world == null) throw new IllegalArgumentException("世界未加载");
        if (world.equals(Bukkit.getWorlds().getFirst()) || !world.getPlayers().isEmpty()) {
            throw new IllegalArgumentException("主世界或有在线玩家的世界不能卸载");
        }
        worlds.put(name, spec.withAutoLoad(false));
        try {
            persist();
        } catch (IOException e) {
            worlds.put(name, spec);
            throw e;
        }
        if (!Bukkit.unloadWorld(world, true)) {
            worlds.put(name, spec);
            persist();
            throw new IllegalArgumentException("服务器拒绝卸载地形世界");
        }
        states.put(name, "unloaded");
        audit(actor, "terrain.unload", name);
    }

    void teleport(String name, String playerName, String actor) {
        requireWritable();
        require(name);
        if (!playerName.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("玩家名称无效");
        World world = Bukkit.getWorld(new NamespacedKey("lbr_terrain", name));
        Player player = Bukkit.getPlayerExact(playerName);
        if (world == null || !states.getOrDefault(name, "").equals("ready")) throw new IllegalArgumentException("地形世界尚未就绪");
        if (player == null) throw new IllegalArgumentException("玩家不在线");
        var location = world.getSpawnLocation();
        location.setX(location.getBlockX() + 0.5);
        location.setZ(location.getBlockZ() + 0.5);
        if (!safeSpawn(location)) {
            throw new IllegalArgumentException("出生点没有安全落脚位置，请先设置安全出生点");
        }
        audit(actor, "terrain.teleport.request", name + " player=" + playerName);
        player.teleportAsync(location).whenComplete((success, error) -> {
            audit(actor, Boolean.TRUE.equals(success) && error == null ? "terrain.teleport.success" : "terrain.teleport.failed",
                    name + " player=" + playerName);
        });
    }

    void setSpawn(String name, Map<String, Object> body, String actor) {
        requireWritable();
        require(name);
        World world = Bukkit.getWorld(new NamespacedKey("lbr_terrain", name));
        if (world == null || !states.getOrDefault(name, "").equals("ready")) {
            throw new IllegalArgumentException("地形世界尚未就绪");
        }
        SpawnCoordinates coordinates = spawnCoordinates(body, world.getMinHeight(), world.getMaxHeight());
        Location location = new Location(world, coordinates.x(), coordinates.y(), coordinates.z(), 0f, 0f);
        if (!safeSpawn(location)) {
            throw new IllegalArgumentException("所选出生点不安全：需要实体地板、两格可通行空间，且不能有危险方块");
        }
        if (!world.setSpawnLocation(location)) throw new IllegalArgumentException("服务器未能设置地形世界出生点");
        Location actual = world.getSpawnLocation();
        if (actual.getBlockX() != coordinates.x() || actual.getBlockY() != coordinates.y()
                || actual.getBlockZ() != coordinates.z()) {
            throw new IllegalArgumentException("出生点被其他插件更改，请刷新后重试");
        }
        world.save();
        audit(actor, "terrain.spawn", name + " x=" + coordinates.x() + " y=" + coordinates.y()
                + " z=" + coordinates.z());
    }

    record SpawnCoordinates(int x, int y, int z) {}

    static SpawnCoordinates spawnCoordinates(Map<String, Object> body, int minHeight, int maxHeight) {
        for (String key : body.keySet()) if (!Set.of("x", "y", "z").contains(key)) {
            throw new IllegalArgumentException("未知出生点字段：" + key);
        }
        return new SpawnCoordinates(spawnCoordinate(body, "x", -29999984, 29999984),
                spawnCoordinate(body, "y", minHeight + 1, maxHeight - 2),
                spawnCoordinate(body, "z", -29999984, 29999984));
    }

    private static int spawnCoordinate(Map<String, Object> body, String key, int min, int max) {
        Object value = body.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException("出生点 " + key + " 必须为整数");
        }
        double coordinate = number.doubleValue();
        if (coordinate < min || coordinate > max) {
            throw new IllegalArgumentException("出生点 " + key + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return (int) coordinate;
    }

    private static boolean safeSpawn(Location location) {
        var floor = location.clone().subtract(0, 1, 0).getBlock();
        var feet = location.getBlock();
        var head = location.clone().add(0, 1, 0).getBlock();
        return floor.getType().isSolid() && feet.isPassable() && head.isPassable()
                && !floor.isLiquid() && !feet.isLiquid() && !head.isLiquid()
                && !hazardous(floor.getType().name()) && !hazardous(feet.getType().name())
                && !hazardous(head.getType().name());
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static boolean hazardous(String material) {
        return Set.of("LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE",
                "POWDER_SNOW", "SWEET_BERRY_BUSH", "WITHER_ROSE").contains(material);
    }
}
