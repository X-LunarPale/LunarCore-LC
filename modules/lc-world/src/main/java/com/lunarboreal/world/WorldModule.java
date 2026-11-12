package com.lunarboreal.world;

import com.lunarboreal.api.module.LcModule;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;

/** All mutable state and Bukkit calls are confined to the server thread. */
public final class WorldModule extends LcModule {
    final Map<String, WorldOptions> worlds = new LinkedHashMap<>();
    final Map<String, String> states = new LinkedHashMap<>();
    final Map<String, String> errors = new LinkedHashMap<>();
    final Set<String> imported = new HashSet<>();
    final Map<String,String> saveRoots = new HashMap<>();
    final Map<String,String> dimensionPaths = new HashMap<>();
    private Path file;
    private boolean storageReady;

    @Override public void onEnable() {
        file = dataFolder().toPath().resolve("worlds.yml");
        try {
            Files.createDirectories(dataFolder().toPath());
            YamlConfiguration yaml = new YamlConfiguration();
            if (Files.exists(file)) yaml.load(file.toFile());
            boolean invalid = false;
            ConfigurationSection section = yaml.getConfigurationSection("worlds");
            if (section != null) for (String name : section.getKeys(false)) {
                try {
                    var s = section.getConfigurationSection(name);
                    Map<String,Object> values = new LinkedHashMap<>(s.getValues(false));
                    var rules = s.getConfigurationSection("gamerules");
                    if (rules != null) values.put("gamerules",rules.getValues(false));
                    worlds.put(name, WorldOptions.parse(name,values,null));
                    if (s.getBoolean("imported",false)) imported.add(name);
                    String rootName = s.getString("saveRoot",name);
                    WorldOptions.validateName(rootName);
                    saveRoots.put(name,rootName);
                    if (s.contains("dimensionPath")) dimensionPaths.put(name,s.getString("dimensionPath"));
                    states.put(name, s.getString("status","registered"));
                    errors.put(name,s.getString("error",""));
                } catch (Exception e) { invalid = true; errors.put(name,e.getMessage()); states.put(name,"failed"); logger().severe("世界配置无效 " + name + ": " + e.getMessage()); }
            }
            storageReady = !invalid;
            if (invalid) logger().severe("部分世界配置无效，已禁用配置写入以保护原文件");
        } catch (Exception e) { logger().severe("无法读取worlds.yml，管理写入已禁用: " + e.getMessage()); }
        registerListener(new DimensionRestrictionListener(this));
        new WorldWeb(this).register();
        for (WorldOptions options : List.copyOf(worlds.values())) if (storageReady && options.loadOnStartup()) {
            try { queueLoad(options,"startup"); } catch (Exception e) { fail(options.name(),e); }
        }
    }

    private Path resolveSaveRoot(World w) throws Exception {
        // Bukkit's public world key can differ from the vanilla dimension key.
        String dimension = switch (w.getEnvironment()) {
            case NORMAL -> "overworld";
            case NETHER -> "the_nether";
            case THE_END -> "the_end";
            default -> throw new IllegalArgumentException("不支持自定义维度目录");
        };
        Path folder = w.getWorldFolder().toPath();
        Path save;
        try { save = WorldOptions.loadedSaveRoot(Bukkit.getWorldContainer().toPath(),w.getName(),folder,"minecraft",dimension); }
        catch (IllegalArgumentException defaultMismatch) {
            // WorldCreator registers a named dimension within the primary save in 26.3.
            save = WorldOptions.loadedSaveRoot(Bukkit.getWorldContainer().toPath(),w.getName(),folder,
                    w.getKey().getNamespace(),w.getKey().getKey());
        }
        String relative = save.relativize(folder.toRealPath()).toString().replace('\\','/');
        if (!relative.isEmpty()) dimensionPaths.put(w.getName(),relative);
        return save;
    }

    WorldOptions snapshot(World w) {
        Location spawn = w.getSpawnLocation();
        WorldType type = w.getWorldType();
        if (type == null) type = WorldType.NORMAL;
        return new WorldOptions(w.getName(),w.getEnvironment(),type,w.getSeed(),true,w.getPVP(),w.getDifficulty(),
                w.getTime(),Boolean.TRUE.equals(w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE)),
                w.isThundering()?"THUNDER":w.hasStorm()?"RAIN":"CLEAR",w.isAutoSave(),true,
                spawn.getBlockX(),spawn.getBlockY(),spawn.getBlockZ(),Map.of());
    }
    void importLoaded(String name,String actor) throws Exception {
        WorldOptions.validateName(name);
        if (worlds.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name))) throw new IllegalArgumentException("世界已注册");
        World w = Bukkit.getWorld(name);
        if (w == null || !w.getName().equals(name)) throw new IllegalArgumentException("只能导入已加载的确切名称世界");
        Path save = resolveSaveRoot(w);
        saveRoots.put(name,save.getFileName().toString());
        WorldOptions options = snapshot(w);
        worlds.put(name,options); imported.add(name); states.put(name,"succeeded"); errors.put(name,"");
        try { validate(options); persist(); }
        catch (Exception e) { worlds.remove(name); imported.remove(name); saveRoots.remove(name); dimensionPaths.remove(name); states.remove(name); errors.remove(name); throw e; }
        audit(actor,"world.import",name+" environment="+w.getEnvironment()+" seed="+w.getSeed());
    }

    void persist() throws Exception {
        if (!storageReady) throw new IllegalStateException("配置存储不可用，请检查启动日志");
        YamlConfiguration yaml = new YamlConfiguration();
        worlds.forEach((name,o) -> {
            String p = "worlds." + name + ".";
            o.map().forEach((k,v) -> yaml.set(p+k,v));
            yaml.set(p+"imported",imported.contains(name));
            yaml.set(p+"saveRoot",saveRoots.getOrDefault(name,name));
            yaml.set(p+"dimensionPath",dimensionPaths.get(name));
            yaml.set(p+"status",states.getOrDefault(name,"registered"));
            yaml.set(p+"error",errors.getOrDefault(name,""));
        });
        Path temp = Files.createTempFile(dataFolder().toPath(),"worlds-",".tmp");
        try {
            Files.writeString(temp,yaml.saveToString());
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    void rejectExistingDimension(String name) throws Exception {
        Path base = Bukkit.getWorldContainer().toPath().toRealPath();
        try (var children = Files.list(base)) {
            for (Path save : children.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(save.resolve("level.dat"))) continue;
                Path dimension = save.resolve("dimensions/minecraft").resolve(name.toLowerCase(Locale.ROOT));
                if (Files.exists(dimension,LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("同名共享存档维度已存在，拒绝创建");
            }
        }
    }

    void validate(WorldOptions o) throws Exception {
        WorldOptions.safePath(Bukkit.getWorldContainer().toPath(),o.name());
        if (!imported.contains(o.name()) && !Set.of(WorldType.NORMAL,WorldType.FLAT,WorldType.LARGE_BIOMES,WorldType.AMPLIFIED).contains(o.type()))
            throw new IllegalArgumentException("未验证该生成类型，仅支持NORMAL/FLAT/LARGE_BIOMES/AMPLIFIED");
        if (!imported.contains(o.name()) && o.environment() != World.Environment.NORMAL && o.type() != WorldType.NORMAL)
            throw new IllegalArgumentException("NETHER/THE_END只支持NORMAL生成类型");
        for (var entry : o.gamerules().entrySet()) {
            GameRule<?> rule = GameRule.getByName(entry.getKey());
            if (rule == null) throw new IllegalArgumentException("未知gamerule: " + entry.getKey());
            if (rule.equals(GameRule.DO_DAYLIGHT_CYCLE) || rule.equals(GameRule.PVP))
                throw new IllegalArgumentException("daylight cycle/PvP请使用专用配置项");
            if (rule.getType() == Boolean.class && !Set.of("true","false").contains(entry.getValue()))
                throw new IllegalArgumentException("布尔gamerule只接受true/false");
            if (rule.getType() == Integer.class) Integer.parseInt(entry.getValue());
        }
        int coords = (o.spawnX()==null?0:1)+(o.spawnY()==null?0:1)+(o.spawnZ()==null?0:1);
        if (coords != 0 && coords != 3) throw new IllegalArgumentException("出生点X/Y/Z必须同时填写");
    }
    void queueLoad(WorldOptions options, String actor) {
        try {
            if (Set.of("queued","running").contains(states.get(options.name())) && !actor.equals("startup"))
                throw new IllegalArgumentException("该世界操作正在执行");
            validate(options);
            states.put(options.name(),"queued"); errors.put(options.name(),""); persist();
            runTask(() -> {
                try {
                    states.put(options.name(),"running"); persist();
                    World world = Bukkit.getWorld(options.name());
                    if (world == null) {
                        String saveName = saveRoots.getOrDefault(options.name(),options.name());
                        Path path = WorldOptions.safePath(Bukkit.getWorldContainer().toPath(),saveName);
                        String dim = dimensionPaths.get(options.name());
                        if (dim != null) {
                            if (!dim.matches("dimensions/[a-z0-9_.-]+/[a-z0-9_.-]+")) throw new IllegalArgumentException("保存的维度路径无效");
                            String[] parts = dim.split("/");
                            Path confirmed = WorldOptions.loadedSaveRoot(Bukkit.getWorldContainer().toPath(),options.name(),path.resolve(dim),parts[1],parts[2]);
                            if (!confirmed.equals(path.toRealPath())) throw new IllegalArgumentException("存档根不符");
                        } else {
                            rejectExistingDimension(options.name());
                            if (!saveName.equals(options.name())) throw new IllegalArgumentException("旧共享存档记录缺维度路径，请在已加载时重新导入");
                            if (imported.contains(options.name()) && !Files.isRegularFile(path.resolve("level.dat"))) throw new IllegalArgumentException("已导入世界数据缺失，拒绝重新生成");
                            if (Files.exists(path) && !Files.isRegularFile(path.resolve("level.dat"))) throw new IllegalArgumentException("现有目录不是可加载世界（缺少level.dat）");
                        }
                        world = new WorldCreator(options.name()).environment(options.environment()).type(options.type()).seed(options.seed()).createWorld();
                    }
                    if (world == null) throw new IllegalStateException("世界加载返回null");
                    if (world.getEnvironment() != options.environment()) throw new IllegalArgumentException("现有世界环境不符");
                    Path save = resolveSaveRoot(world);
                    saveRoots.put(options.name(),save.getFileName().toString());
                    apply(world,options);
                    states.put(options.name(),"succeeded"); errors.put(options.name(),""); persist();
                    audit(actor,"world.load",options.name());
                } catch (Exception e) { fail(options.name(),e); }
            });
        } catch (Exception e) { throw new IllegalArgumentException(e.getMessage(),e); }
    }
    void fail(String name,Exception e) {
        states.put(name,"failed"); errors.put(name,String.valueOf(e.getMessage()));
        logger().severe("世界操作失败 " + name + ": " + e.getMessage());
        try { persist(); } catch (Exception write) { logger().severe("保存失败状态失败: " + write.getMessage()); }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    void apply(World w, WorldOptions o) {
        if (o.spawnY()!=null && (o.spawnY()<w.getMinHeight() || o.spawnY()>=w.getMaxHeight())) throw new IllegalArgumentException("出生点Y不在世界高度内");
        for (var entry : o.gamerules().entrySet()) {
            GameRule rule = GameRule.getByName(entry.getKey());
            Object value = rule.getType()==Boolean.class ? Boolean.valueOf(entry.getValue()) : Integer.valueOf(entry.getValue());
            if (!w.setGameRule(rule,value)) throw new IllegalArgumentException("gamerule未被世界接受: " + entry.getKey());
        }
        w.setPVP(o.pvp()); w.setDifficulty(o.difficulty()); w.setAutoSave(o.autoSave());
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,o.daylightCycle());
        // Bukkit exposes no clock-capability API; only the standard NORMAL environment has a verified clock.
        if (w.getEnvironment() == World.Environment.NORMAL) w.setTime(o.time());
        w.setStorm(!o.weather().equals("CLEAR")); w.setThundering(o.weather().equals("THUNDER"));
        if (o.spawnX()!=null && !w.setSpawnLocation(o.spawnX(),o.spawnY(),o.spawnZ())) throw new IllegalArgumentException("无法设置出生点");
    }
    WorldOptions require(String name) {
        WorldOptions o = worlds.get(WorldOptions.validateName(name));
        if (o==null) throw new IllegalArgumentException("世界未注册");
        return o;
    }
    void unload(String name,String actor) throws Exception {
        WorldOptions old = require(name);
        if (Set.of("queued","running").contains(states.get(name))) throw new IllegalArgumentException("操作执行中");
        World w = Bukkit.getWorld(name);
        if (w==null) throw new IllegalArgumentException("世界未加载");
        if (Bukkit.getWorlds().getFirst().equals(w)) throw new IllegalArgumentException("禁止卸载主世界");
        if (!w.getPlayers().isEmpty()) throw new IllegalArgumentException("世界仍有在线玩家");
        WorldOptions disabled = WorldOptions.parse(name,Map.of("loadOnStartup",false),old);
        worlds.put(name,disabled);
        try { persist(); } catch (Exception e) { worlds.put(name,old); throw e; }
        if (!Bukkit.unloadWorld(w,true)) { worlds.put(name,old); persist(); throw new IllegalArgumentException("服务器拒绝卸载世界"); }
        states.put(name,"unloaded"); persist(); audit(actor,"world.unload",name);
    }
}
