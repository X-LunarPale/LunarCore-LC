package com.lunarboreal.core.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed-scope Paper 26.3 file editor. Never invokes Bukkit/Paper reload or edits world files. */
final class PaperConfigApi {
    private static final Object SAVE_LOCK = new Object();
    private static final int CONFIG_VERSION = 31;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final String NOTICE = "保存后需重启服务器生效；世界默认配置可能被各世界覆盖。未收录设置保持原样，不支持在线编辑。";
    private static final Map<String, List<Field>> FIELDS = schema();
    private final Path root;

    PaperConfigApi(Path serverRoot) {
        root = serverRoot.toAbsolutePath().normalize();
    }

    void register(WebServer web) {
        web.routes.add("GET", "/api/config/paper/{scope}", "core.config.edit", false,
                ctx -> ctx.json(read(ctx.pathParam("scope"))), web, "core");
        web.routes.add("POST", "/api/config/paper/{scope}", "core.config.edit", false,
                ctx -> ctx.json(patch(ctx.pathParam("scope"), ctx.bodyMap())), web, "core");
    }

    Map<String, Object> read(String scope) throws IOException {
        synchronized (SAVE_LOCK) {
            Snapshot snapshot = load(scope);
            return response(scope, snapshot);
        }
    }

    Map<String, Object> patch(String scope, Map<String, Object> request) throws IOException {
        synchronized (SAVE_LOCK) {
            List<Field> fields = fields(scope);
            if (request == null || !request.keySet().equals(Set.of("fingerprint", "patch")))
                throw bad("请求仅允许 fingerprint 和 patch 字段");
            if (!(request.get("fingerprint") instanceof String expected) || !expected.matches("sha256:[a-f0-9]{64}"))
                throw bad("缺少或无效的配置指纹，请先读取配置");
            if (!(request.get("patch") instanceof Map<?, ?> changes) || changes.isEmpty() || changes.size() > fields.size())
                throw bad("patch 必须是非空配置键值对象");
            Snapshot original = load(scope);
            if (!expected.equals(original.fingerprint)) throw conflict("配置已被修改，请刷新后重新保存");
            if (!supported(original.yaml))
                throw conflict("当前 Paper 配置版本不受支持，仅支持版本 31");
            Map<String, Object> validated = new LinkedHashMap<>();
            for (var entry : changes.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw bad("配置键必须是字符串");
                Field field = fields.stream().filter(f -> f.key.equals(key)).findFirst()
                        .orElseThrow(() -> bad("不允许修改配置项：" + key));
                if (!editable(original.yaml, field)) throw conflict("配置项缺失或类型不兼容：" + key);
                validated.put(key, field.validate(entry.getValue()));
            }
            List<String> changed = new ArrayList<>();
            for (var entry : validated.entrySet()) {
                if (!equivalent(original.yaml.get(entry.getKey()), entry.getValue())) {
                    original.yaml.set(entry.getKey(), entry.getValue());
                    changed.add(entry.getKey());
                }
            }
            if (changed.isEmpty()) return saved(scope, original.fingerprint, changed);
            validateRelations(original.yaml, scope);
            byte[] updated = original.yaml.saveToString().getBytes(StandardCharsets.UTF_8);
            // Verify serialization before creating any backup or replacing the live file.
            parse(updated);
            Path target = file(scope);
            Path temp = Files.createTempFile(target.getParent(), ".paper-edit-", ".tmp");
            try {
                Files.write(temp, updated, StandardOpenOption.TRUNCATE_EXISTING);
                if (!expected.equals(load(scope).fingerprint)) throw conflict("配置已被修改，请刷新后重新保存");
                Path backup = Files.createTempFile(target.getParent(), target.getFileName() + ".lbr-backup-", ".yml");
                try {
                    Files.write(backup, original.bytes, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    Files.deleteIfExists(backup);
                    throw e;
                }
                // A second check also protects against edits during backup creation. External processes
                // do not share SAVE_LOCK; as with any file editor, they must not write during replacement.
                if (!expected.equals(load(scope).fingerprint)) throw conflict("配置已被修改，请刷新后重新保存");
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return saved(scope, fingerprint(updated), changed);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static Map<String, Object> saved(String scope, String fingerprint, List<String> changed) {
        return Map.of("ok", true, "scope", scope, "fingerprint", fingerprint, "changedKeys", changed,
                "restartRequired", true, "message", "配置已保存，重启服务器后生效。");
    }

    private Map<String, Object> response(String scope, Snapshot snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> schema = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> known = new java.util.HashSet<>();
        boolean supported = supported(snapshot.yaml);
        for (Field field : fields(scope)) {
            known.add(field.key);
            Object value = snapshot.yaml.get(field.key);
            // Never expose nested sections or unexpected lists under scalar allowlisted keys.
            values.put(field.key, value instanceof String || value instanceof Number || value instanceof Boolean ? value : null);
            Map<String, Object> meta = field.metadata();
            meta.put("present", snapshot.yaml.contains(field.key));
            meta.put("editable", supported && editable(snapshot.yaml, field));
            schema.add(meta);
        }
        List<String> unknown = snapshot.yaml.getKeys(true).stream()
                .filter(k -> !snapshot.yaml.isConfigurationSection(k) && !known.contains(k)).sorted().toList();
        out.put("scope", scope);
        out.put("fingerprint", snapshot.fingerprint);
        out.put("configVersion", snapshot.yaml.get("_version"));
        out.put("restartRequired", true);
        out.put("notice", NOTICE);
        out.put("fields", schema);
        out.put("values", values);
        out.put("unknownKeys", unknown);
        out.put("unknownPolicy", "未收录项仅展示键名且保持原样；敏感值不返回。");
        out.put("defaultValueMeaning", "Paper 26.3 参考缺省值；不代表运行时有效值。缺失字段不允许写入。");
        return out;
    }

    private static boolean supported(YamlConfiguration yaml) {
        Object version = yaml.get("_version");
        return version instanceof Number && equivalent(version, CONFIG_VERSION);
    }

    private static boolean editable(YamlConfiguration yaml, Field field) {
        if (!yaml.contains(field.key)) return false;
        try { field.validate(yaml.get(field.key)); return true; }
        catch (HttpException e) { return false; }
    }

    private Snapshot load(String scope) throws IOException {
        Path file = file(scope);
        if (Files.size(file) > MAX_FILE_BYTES) throw bad("配置文件过大，不支持在线编辑");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_FILE_BYTES) throw bad("配置文件过大，不支持在线编辑");
        return new Snapshot(bytes, parse(bytes), fingerprint(bytes));
    }

    private Path file(String scope) throws IOException {
        fields(scope); // Reject traversal and unknown scopes before any filesystem access.
        Path realRoot = root.toRealPath();
        Path directory = realRoot.resolve("config");
        Path target = directory.resolve(scope.equals("global") ? "paper-global.yml" : "paper-world-defaults.yml");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(target)) throw bad("配置路径不允许符号链接");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw HttpException.notFound("Paper 配置文件不存在");
        if (!directory.toRealPath().equals(directory) || !target.toRealPath().equals(target))
            throw bad("配置路径必须位于服务器 config 目录内");
        return target;
    }

    private static YamlConfiguration parse(byte[] bytes) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try { yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8)); }
        catch (InvalidConfigurationException e) { throw conflict("Paper YAML 配置格式无效，请先修复文件"); }
        return yaml;
    }

    private static String fingerprint(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static List<Field> fields(String scope) {
        List<Field> result = FIELDS.get(scope);
        if (result == null) throw bad("不支持的配置范围，仅允许 global 或 world-defaults");
        return result;
    }

    private static boolean equivalent(Object a, Object b) {
        if (a instanceof Number && b instanceof Number)
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        return java.util.Objects.equals(a, b);
    }

    private static void validateRelations(YamlConfiguration yaml, String scope) {
        if (!scope.equals("world-defaults")) return;
        ordered(yaml, "fishing-time-range.minimum", "fishing-time-range.maximum");
        ordered(yaml, "entities.behavior.phantoms-spawn-attempt-min-seconds", "entities.behavior.phantoms-spawn-attempt-max-seconds");
    }

    private static void ordered(YamlConfiguration yaml, String min, String max) {
        if (yaml.get(min) instanceof Number && yaml.get(max) instanceof Number && yaml.getDouble(min) > yaml.getDouble(max))
            throw bad(min + " 不得大于 " + max);
    }

    private static HttpException bad(String message) { return HttpException.badRequest(message); }
    private static HttpException conflict(String message) { return new HttpException(409, message); }
    private record Snapshot(byte[] bytes, YamlConfiguration yaml, String fingerprint) {}

    private record Field(String key, String name, String description, String group, String type,
                         Object defaultValue, String unit, Double min, Double max, Double step,
                         List<String> options, Map<String, String> sentinel) {
        Object validate(Object value) {
            if (value == null) throw bad(name + " 不允许为空");
            if (type.equals("boolean")) {
                if (!(value instanceof Boolean)) throw bad(name + " 必须为布尔值");
                return value;
            }
            if (type.equals("enum")) {
                if (!(value instanceof String s) || !options.contains(s)) throw bad(name + " 必须为允许的枚举值");
                return value;
            }
            if (value instanceof String text) {
                if (sentinel.containsKey(text) && !text.matches("-?\\d+(\\.\\d+)?")) return text;
                throw bad(name + " 必须为数值或允许的特殊值");
            }
            if (!(value instanceof Number number)) throw bad(name + " 必须为数值");
            BigDecimal decimal;
            try { decimal = new BigDecimal(number.toString()); }
            catch (NumberFormatException e) { throw bad(name + " 必须为有限数值"); }
            if (type.equals("integer") && decimal.stripTrailingZeros().scale() > 0) throw bad(name + " 必须为整数");
            boolean special = sentinel.keySet().stream().filter(s -> s.matches("-?\\d+(\\.\\d+)?"))
                    .anyMatch(s -> decimal.compareTo(new BigDecimal(s)) == 0);
            if (!special && (decimal.compareTo(BigDecimal.valueOf(min)) < 0 || decimal.compareTo(BigDecimal.valueOf(max)) > 0))
                throw bad(name + " 超出允许范围");
            try { return type.equals("integer") ? (Object) decimal.intValueExact() : decimal.doubleValue(); }
            catch (ArithmeticException e) { throw bad(name + " 超出整数范围"); }
        }

        Map<String, Object> metadata() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key); m.put("name", name); m.put("description", description); m.put("group", group);
            m.put("type", type); m.put("defaultValue", defaultValue); m.put("unit", unit);
            m.put("min", min); m.put("max", max); m.put("step", step); m.put("options", options); m.put("sentinel", sentinel);
            return m;
        }
    }

    private static Field bool(String key, String name, String group, boolean value, String description) {
        return new Field(key, name, description, group, "boolean", value, "", null, null, null, List.of(), Map.of());
    }
    private static Field integer(String key, String name, String group, Object value, String unit, double min, double max, Map<String, String> sentinel, String description) {
        return new Field(key, name, description, group, "integer", value, unit, min, max, 1d, List.of(), sentinel);
    }
    private static Field number(String key, String name, String group, Object value, String unit, double min, double max, double step, Map<String, String> sentinel, String description) {
        return new Field(key, name, description, group, "number", value, unit, min, max, step, List.of(), sentinel);
    }
    private static Field enumeration(String key, String name, String group, String value, List<String> options, String description) {
        return new Field(key, name, description, group, "enum", value, "", null, null, null, options, Map.of());
    }

    private static Map<String, List<Field>> schema() {
        Map<String, String> inherited = Map.of("-1", "使用上级配置或 Paper 自动值");
        Map<String, String> automatic = Map.of("-1", "由 Paper 自动决定");
        Map<String, String> defaults = Map.of("default", "使用 Paper 或上级配置缺省值");
        List<Field> global = new ArrayList<>();
        global.add(bool("collisions.enable-player-collisions", "玩家碰撞", "碰撞", true, "控制玩家之间的碰撞。"));
        global.add(bool("collisions.send-full-pos-for-hard-colliding-entities", "高精度碰撞实体位置", "碰撞", true, "向客户端发送硬碰撞实体的完整位置。"));
        global.add(bool("chunk-loading-advanced.auto-config-send-distance", "自动区块发送距离", "区块加载", true, "根据客户端加载情况自动调整区块发送距离。"));
        global.add(integer("chunk-loading-advanced.player-max-concurrent-chunk-generates", "玩家并发区块生成", "区块加载", 0, "个", 1, 1024, Map.of("0", "由 Paper 自动决定"), "限制单个玩家同时生成的区块数。"));
        global.add(integer("chunk-loading-advanced.player-max-concurrent-chunk-loads", "玩家并发区块读取", "区块加载", 0, "个", 1, 1024, Map.of("0", "由 Paper 自动决定"), "限制单个玩家同时读取的区块数。"));
        global.add(number("chunk-loading-basic.player-max-chunk-generate-rate", "玩家区块生成速率", "区块加载", -1d, "区块/秒", 0.01, 10000, 1, Map.of("-1", "不限速"), "限制每名玩家的区块生成速率。"));
        global.add(number("chunk-loading-basic.player-max-chunk-load-rate", "玩家区块读取速率", "区块加载", 100d, "区块/秒", 0.01, 10000, 1, Map.of("-1", "不限速"), "限制每名玩家的区块磁盘读取速率。"));
        global.add(number("chunk-loading-basic.player-max-chunk-send-rate", "玩家区块发送速率", "区块加载", 75d, "区块/秒", 0.01, 10000, 1, Map.of("-1", "不限速"), "限制向每名玩家发送区块的速率。"));
        global.add(integer("chunk-system.io-threads", "区块读写线程", "区块加载", -1, "线程", 1, 256, automatic, "区块磁盘读写线程数。"));
        global.add(integer("chunk-system.worker-threads", "区块工作线程", "区块加载", -1, "线程", 1, 256, automatic, "区块处理工作线程数。"));
        global.add(bool("misc.enable-nether", "启用下界", "游戏规则", true, "允许服务器加载下界。"));
        global.add(bool("misc.prevent-negative-villager-demand", "禁止村民负需求", "游戏规则", false, "防止村民交易需求降为负数。"));
        global.add(integer("misc.max-joins-per-tick", "每刻加入人数", "连接与网络", 5, "人/刻", 1, 1000, Map.of(), "限制每个游戏刻处理的玩家加入数量。"));
        global.add(integer("misc.region-file-cache-size", "区域文件缓存", "存储与保存", 256, "个", 1, 16384, Map.of(), "限制同时缓存的区域文件数。"));
        global.add(integer("misc.compression-level", "网络压缩等级", "连接与网络", "default", "级", 0, 9, defaults, "设置网络压缩等级，较高等级增加 CPU 使用。"));
        global.add(integer("player-auto-save.max-per-tick", "每刻玩家保存上限", "存储与保存", -1, "人/刻", 1, 10000, automatic, "限制每个游戏刻自动保存的玩家数。"));
        global.add(integer("player-auto-save.rate", "玩家自动保存间隔", "存储与保存", -1, "刻", 1, 720000, inherited, "玩家数据的自动保存间隔。"));
        global.add(enumeration("packet-limiter.all-packets.action", "超限数据包处理", "连接与网络", "KICK", List.of("KICK", "DROP"), "数据包超限时断开连接或丢弃数据包。"));
        global.add(number("packet-limiter.all-packets.interval", "数据包统计周期", "连接与网络", 7d, "秒", 0.1, 300, 0.1, Map.of(), "统计数据包平均速率的时间窗口。"));
        global.add(number("packet-limiter.all-packets.max-packet-rate", "数据包速率上限", "连接与网络", 500d, "包/秒", 1, 100000, 1, Map.of(), "所有数据包的平均速率上限。"));
        global.add(bool("scoreboards.save-empty-scoreboard-teams", "保存空计分板队伍", "计分板", true, "保存没有成员的计分板队伍。"));
        global.add(bool("scoreboards.track-plugin-scoreboards", "跟踪插件计分板", "计分板", false, "跟踪插件创建的计分板。"));
        global.add(bool("spark.enabled", "启用性能分析器", "监控", true, "启用 Paper 自带的 Spark 性能分析器。"));
        global.add(bool("spark.enable-immediately", "立即初始化性能分析器", "监控", false, "服务器启动时初始化性能分析器。"));
        global.add(bool("update-checker.enabled", "检查 Paper 更新", "监控", true, "启用 Paper 更新检查。"));
        global.add(integer("watchdog.early-warning-delay", "卡顿警告起始时间", "监控", 10000, "毫秒", 0, 3600000, Map.of(), "主线程无响应达到此时间后开始输出警告。"));
        global.add(integer("watchdog.early-warning-every", "卡顿警告输出间隔", "监控", 5000, "毫秒", 0, 3600000, Map.of("0", "关闭提前警告"), "主线程无响应时提前警告的输出间隔。"));
        for (String block : List.of("chorus-plant", "mushroom-block", "noteblock", "tripwire")) {
            String name = switch (block) { case "chorus-plant" -> "紫颂植物"; case "mushroom-block" -> "蘑菇方块"; case "noteblock" -> "音符盒"; default -> "绊线"; };
            global.add(bool("block-updates.disable-" + block + "-updates", "禁用" + name + "更新", "方块更新", false, "禁用" + name + "的方块状态更新，可能改变原版行为。"));
        }
        List<Field> world = new ArrayList<>();
        world.add(bool("anticheat.anti-xray.enabled", "启用反透视", "反透视", false, "通过隐藏矿物信息减少客户端透视作弊。"));
        world.add(integer("anticheat.anti-xray.engine-mode", "反透视引擎模式", "反透视", 1, "模式", 1, 3, Map.of(), "模式 1 隐藏矿物，模式 2/3 使用随机替换；调整后需重启。"));
        world.add(integer("anticheat.anti-xray.max-block-height", "反透视最高高度", "反透视", 64, "格", -64, 320, Map.of(), "反透视处理的最高方块高度。"));
        world.add(integer("anticheat.anti-xray.update-radius", "反透视更新半径", "反透视", 2, "格", 0, 2, Map.of(), "方块变化后恢复真实方块信息的半径。"));
        world.add(bool("anticheat.anti-xray.lava-obscures", "熔岩遮挡矿物", "反透视", false, "将熔岩视为可遮挡矿物的方块。"));
        world.add(bool("anticheat.anti-xray.use-permission", "反透视绕过权限", "反透视", false, "启用反透视权限检查，拥有对应权限的玩家可绕过。"));
        world.add(integer("chunks.auto-save-interval", "区块自动保存间隔", "区块与保存", "default", "刻", 1, 720000, defaults, "设置区块自动保存间隔。"));
        world.add(integer("chunks.max-auto-save-chunks-per-tick", "每刻区块保存上限", "区块与保存", 24, "区块/刻", 1, 10000, Map.of(), "限制单刻自动保存的区块数。"));
        world.add(bool("chunks.flush-regions-on-save", "保存时刷新区域文件", "区块与保存", false, "保存时将区域文件数据刷新到磁盘，可能增加延迟。"));
        world.add(bool("chunks.prevent-moving-into-unloaded-chunks", "阻止进入未加载区块", "区块与保存", false, "阻止玩家进入尚未完成加载的区块。"));
        world.add(integer("chunks.min-chunk-unload-count", "最少卸载区块数", "区块与保存", 50, "区块", 0, 100000, Map.of(), "每轮区块卸载的最小数量。"));
        world.add(number("chunks.min-chunk-unload-fraction", "区块卸载最小比例", "区块与保存", 0.05, "比例", 0, 1, 0.01, Map.of(), "每轮区块卸载的最小比例。"));
        world.add(integer("collisions.max-entity-collisions", "实体碰撞上限", "实体与碰撞", 8, "次", 0, 1024, Map.of(), "每个实体每刻处理的碰撞数量上限。"));
        world.add(bool("collisions.only-players-collide", "仅玩家参与碰撞", "实体与碰撞", false, "仅处理涉及玩家的实体碰撞。"));
        world.add(bool("collisions.allow-vehicle-collisions", "载具碰撞", "实体与碰撞", true, "允许载具发生碰撞。"));
        world.add(bool("collisions.allow-player-cramming-damage", "玩家拥挤伤害", "实体与碰撞", false, "允许玩家承受实体拥挤伤害。"));
        world.add(bool("entities.armor-stands.tick", "盔甲架运算", "实体与碰撞", true, "允许盔甲架执行每刻逻辑。"));
        world.add(bool("entities.armor-stands.do-collision-entity-lookups", "盔甲架碰撞检测", "实体与碰撞", true, "为盔甲架查询附近碰撞实体。"));
        world.add(bool("entities.markers.tick", "标记实体运算", "实体与碰撞", true, "允许标记实体执行每刻逻辑。"));
        world.add(bool("entities.spawning.per-player-mob-spawns", "按玩家计算生物生成", "生物生成", true, "按每名玩家计算生物生成额度。"));
        world.add(bool("entities.spawning.count-all-mobs-for-spawning", "生成计数包含全部生物", "生物生成", false, "将所有适用生物纳入生成数量统计。"));
        world.add(enumeration("entities.spawning.despawn-range-shape", "生物消失范围形状", "生物生成", "ELLIPSOID", List.of("ELLIPSOID", "CYLINDER"), "选择生物消失距离判定的空间形状。"));
        world.add(enumeration("entities.spawning.duplicate-uuid.mode", "重复实体标识处理", "生物生成", "SAFE_REGEN", List.of("SAFE_REGEN", "DELETE", "NOTHING", "WARN"), "发现重复实体 UUID 时的处理方式。"));
        world.add(integer("entities.spawning.monster-spawn-max-light-level", "怪物生成最大亮度", "生物生成", "default", "亮度", 0, 15, defaults, "覆盖怪物生成所允许的最大方块亮度。"));
        for (String category : List.of("ambient", "axolotls", "creature", "monster", "underground_water_creature", "water_ambient", "water_creature")) {
            String name = switch (category) { case "ambient" -> "环境生物"; case "axolotls" -> "美西螈"; case "creature" -> "动物"; case "monster" -> "怪物"; case "underground_water_creature" -> "地下水生生物"; case "water_ambient" -> "水生环境生物"; default -> "水生生物"; };
            world.add(integer("entities.spawning.spawn-limits." + category, name + "生成上限", "生物生成", -1, "只", 0, 10000, inherited, "设置" + name + "的生成数量上限，-1 继承上级配置。"));
            world.add(integer("entities.spawning.ticks-per-spawn." + category, name + "生成间隔", "生物生成", -1, "刻", 1, 720000, inherited, "设置" + name + "的生成尝试间隔，-1 继承上级配置。"));
        }
        world.add(bool("entities.behavior.phantoms-do-not-spawn-on-creative-players", "创造玩家免生成幻翼", "生物行为", true, "不因创造模式玩家而生成幻翼。"));
        world.add(bool("entities.behavior.phantoms-only-attack-insomniacs", "幻翼仅攻击失眠玩家", "生物行为", true, "幻翼仅攻击长时间未睡眠的玩家。"));
        world.add(integer("entities.behavior.phantoms-spawn-attempt-min-seconds", "幻翼最短尝试间隔", "生物行为", 60, "秒", 1, 86400, Map.of(), "幻翼生成尝试间隔的最小值。"));
        world.add(integer("entities.behavior.phantoms-spawn-attempt-max-seconds", "幻翼最长尝试间隔", "生物行为", 119, "秒", 1, 86400, Map.of(), "幻翼生成尝试间隔的最大值。"));
        world.add(integer("entities.behavior.player-insomnia-start-ticks", "玩家失眠起始时间", "生物行为", 72000, "刻", 0, 10000000, Map.of(), "玩家未睡眠达到此时间后视为失眠。"));
        world.add(bool("entities.behavior.disable-player-crits", "禁用玩家暴击", "生物行为", false, "禁用玩家近战暴击。"));
        world.add(bool("entities.behavior.disable-chest-cat-detection", "忽略箱子上的猫", "生物行为", false, "允许打开上方有猫的箱子。"));
        world.add(bool("entities.behavior.pillager-patrols.disable", "禁用掠夺者巡逻队", "生物行为", false, "禁止自然生成掠夺者巡逻队。"));
        world.add(number("entities.behavior.pillager-patrols.spawn-chance", "巡逻队生成概率", "生物行为", 0.2, "概率", 0, 1, 0.01, Map.of(), "每次巡逻队生成尝试成功的概率。"));
        world.add(integer("entities.behavior.zombie-villager-infection-chance", "村民感染概率", "生物行为", "default", "%", 0, 100, defaults, "村民被僵尸击杀后转化的概率。"));
        world.add(bool("environment.optimize-explosions", "优化爆炸计算", "环境", false, "启用爆炸计算优化。"));
        world.add(bool("environment.disable-explosion-knockback", "禁用爆炸击退", "环境", false, "禁用爆炸产生的实体击退。"));
        world.add(bool("environment.disable-thunder", "禁用雷暴", "环境", false, "禁用世界中的雷暴。"));
        world.add(bool("environment.disable-ice-and-snow", "禁用冰雪形成", "环境", false, "禁用天气造成的结冰与积雪。"));
        world.add(integer("environment.max-block-ticks", "方块计划刻上限", "环境", 65536, "次/刻", 1, 1000000, Map.of(), "每个游戏刻处理的方块计划更新上限。"));
        world.add(integer("environment.max-fluid-ticks", "流体计划刻上限", "环境", 65536, "次/刻", 1, 1000000, Map.of(), "每个游戏刻处理的流体计划更新上限。"));
        world.add(integer("environment.portal-create-radius", "传送门创建半径", "环境", 16, "格", 0, 1024, Map.of(), "寻找可创建传送门位置的半径。"));
        world.add(integer("environment.portal-search-radius", "传送门搜索半径", "环境", 128, "格", 0, 1024, Map.of(), "寻找已有传送门的搜索半径。"));
        world.add(number("environment.void-damage-amount", "虚空伤害", "环境", 4d, "生命值", 0, 10000, 0.5, Map.of(), "实体处于虚空时每次受到的伤害。"));
        world.add(bool("hopper.cooldown-when-full", "漏斗满载冷却", "漏斗", true, "目标容器已满时让漏斗进入冷却。"));
        world.add(bool("hopper.disable-move-event", "禁用漏斗移动事件", "漏斗", false, "关闭物品移动事件；可能影响依赖该事件的保护插件。"));
        world.add(bool("hopper.ignore-occluding-blocks", "漏斗忽略遮挡方块", "漏斗", false, "漏斗查找容器时忽略遮挡方块。"));
        world.add(enumeration("misc.redstone-implementation", "红石实现", "红石", "VANILLA", List.of("VANILLA", "EIGENCRAFT", "ALTERNATE_CURRENT"), "选择红石更新实现，可能改变红石装置行为。"));
        world.add(bool("misc.update-pathfinding-on-block-update", "方块更新触发寻路", "生物行为", true, "方块更新时重新计算受影响生物的路径。"));
        world.add(bool("misc.disable-end-credits", "禁用终末之诗", "环境", false, "玩家离开末地时不显示终末之诗。"));
        world.add(integer("fishing-time-range.minimum", "钓鱼最短等待时间", "环境", 100, "刻", 1, 72000, Map.of(), "鱼上钩等待时间的最小值。"));
        world.add(integer("fishing-time-range.maximum", "钓鱼最长等待时间", "环境", 600, "刻", 1, 72000, Map.of(), "鱼上钩等待时间的最大值。"));
        world.add(integer("maps.item-frame-cursor-limit", "地图展示框光标上限", "地图", 128, "个", 0, 10000, Map.of(), "地图中展示框光标的数量上限。"));
        world.add(integer("maps.item-frame-cursor-update-interval", "地图光标更新间隔", "地图", 10, "刻", 1, 72000, Map.of(), "展示框地图光标的更新间隔。"));
        world.add(bool("lootables.auto-replenish", "战利品自动补充", "战利品", false, "允许战利品容器定期补充物品。"));
        world.add(integer("lootables.max-refills", "战利品补充次数", "战利品", -1, "次", 0, 100000, Map.of("-1", "不限次数"), "限制战利品容器自动补充次数。"));
        world.add(bool("lootables.restrict-player-reloot", "限制玩家重复搜刮", "战利品", true, "限制同一玩家重复获取补充后的战利品。"));
        for (String tick : List.of("container-update", "dry-farmland", "grass-spread", "mob-spawner", "wet-farmland")) {
            String name = switch (tick) { case "container-update" -> "容器更新"; case "dry-farmland" -> "干燥耕地"; case "grass-spread" -> "草方块蔓延"; case "mob-spawner" -> "刷怪笼"; default -> "湿润耕地"; };
            world.add(integer("tick-rates." + tick, name + "运算间隔", "运算频率", 1, "刻", 1, 72000, Map.of(), "设置" + name + "逻辑的执行间隔，增大会降低更新频率。"));
        }
        return Map.of("global", List.copyOf(global), "world-defaults", List.copyOf(world));
    }
}
