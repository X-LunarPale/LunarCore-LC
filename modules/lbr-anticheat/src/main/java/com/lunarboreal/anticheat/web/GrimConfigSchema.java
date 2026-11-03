package com.lunarboreal.anticheat.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Metadata for existing GrimAC settings. Missing keys are never synthesized. */
final class GrimConfigSchema {
    private static final int MAX_TEXT = 32768, MAX_ITEMS = 512;
    private static final Pattern PUNISHMENT = Pattern.compile("([0-9]+):([0-9]+) (\\S[^\\r\\n\\u0000]*)");
    private static final Set<String> WAIT_STRATEGIES = Set.of("BLOCKING", "TIMEOUT_BLOCKING", "SLEEPING", "YIELDING", "BUSY_SPIN");
    private static final Map<String, String> CHECKS = Map.ofEntries(
            Map.entry("Simulation", "移动模拟"), Map.entry("Phase", "穿墙检测"),
            Map.entry("AirLiquidPlace", "空气与液体放置"), Map.entry("FabricatedPlace", "伪造方块放置"),
            Map.entry("FarPlace", "远距离放置"), Map.entry("PositionPlace", "异常位置放置"),
            Map.entry("RotationPlace", "异常视角放置"), Map.entry("NoSlow", "使用物品减速"),
            Map.entry("Knockback", "攻击击退"), Map.entry("Explosion", "爆炸击退"),
            Map.entry("TimerA", "客户端计时"), Map.entry("TimerLimit", "计时延迟限制"),
            Map.entry("NegativeTimer", "慢速计时"), Map.entry("VehicleTimer", "载具计时"),
            Map.entry("PacketOrderI", "数据包顺序"), Map.entry("Reach", "攻击距离"),
            Map.entry("Post", "操作时序"), Map.entry("BadPackets", "异常数据包"),
            Map.entry("Hitboxes", "实体碰撞箱"), Map.entry("Misc", "其他行为"),
            Map.entry("Combat", "战斗行为"), Map.entry("Autoclicker", "自动点击"));
    private static final Map<String, String> CONFIG = configLabels();
    private static final Map<String, String> MESSAGES = messageLabels();
    private static final Map<String, String> DATABASE = databaseLabels();
    private static final Map<String, String> BACKEND = backendLabels();
    private static final Map<String, String> DISCORD = Map.ofEntries(
            Map.entry("enabled", "启用 Discord 告警"), Map.entry("webhook", "Webhook 地址"),
            Map.entry("disable-webhook-validation", "跳过 Webhook 地址验证"),
            Map.entry("embed-title", "嵌入消息标题"), Map.entry("embed-color", "嵌入消息颜色"),
            Map.entry("include-timestamp", "显示发送时间"),
            Map.entry("backtick-replacement-char", "反引号替换字符"), Map.entry("violation-content", "违规消息内容"));

    record Field(String key, String label, String group, String type, String help,
                 boolean readOnly, boolean secret, Double min, Double max, Double step,
                 List<String> options) {
        Map<String, Object> metadata() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key); out.put("label", label); out.put("group", group); out.put("type", type);
            out.put("help", help); out.put("readOnly", readOnly); out.put("secret", secret);
            if (label.equals("未收录配置")) out.put("hidden", true);
            if (min != null) out.put("min", min);
            if (max != null) out.put("max", max);
            if (step != null) out.put("step", step);
            if (!options.isEmpty()) out.put("options", options.stream().map(v -> Map.of("value", v, "label", optionLabel(v))).toList());
            return out;
        }

        Object validate(Object value) {
            if (readOnly) throw bad("此配置项仅可查看：" + key);
            if (type.equals("bool")) {
                if (!(value instanceof Boolean)) throw bad(label + "必须为开关值");
                return value;
            }
            if (type.equals("number")) {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
                    throw bad(label + "必须为有限数值");
                BigDecimal decimal;
                try { decimal = new BigDecimal(number.toString()); }
                catch (NumberFormatException e) { throw bad(label + "必须为有限数值"); }
                if (min != null && decimal.compareTo(BigDecimal.valueOf(min)) < 0
                        || max != null && decimal.compareTo(BigDecimal.valueOf(max)) > 0)
                    throw bad(label + "超出允许范围");
                if ((key.endsWith(".immediate-setback-threshold") || key.endsWith(".max-advantage"))
                        && decimal.signum() < 0 && decimal.compareTo(BigDecimal.valueOf(-1)) != 0)
                    throw bad(label + "只允许非负数或 -1");
                if (step != null && step == 1d) {
                    long result;
                    try { result = decimal.longValueExact(); }
                    catch (ArithmeticException e) { throw bad(label + "必须为整数"); }
                    if (key.equals("database.write-path.queue-capacity") && (result <= 0 || (result & (result - 1)) != 0))
                        throw bad("写入队列容量必须为正的二次幂");
                    if (key.equals("update-permission-ticks") && result != -1 && result <= 0)
                        throw bad("权限刷新间隔只能为 -1 或正整数");
                    return result;
                }
                return number.doubleValue();
            }
            if (type.equals("string-list") || type.equals("punishment-commands")) {
                if (!(value instanceof List<?> items) || items.size() > MAX_ITEMS) throw bad(label + "必须为不超过 512 项的文本列表");
                List<String> result = new ArrayList<>();
                for (Object item : items) {
                    String text = text(item);
                    if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) throw bad(label + "的单项不能包含换行");
                    if (type.equals("punishment-commands")) validateCommand(text);
                    if (key.equals("client-brand.ignored-clients")) {
                        try { Pattern.compile(text); }
                        catch (PatternSyntaxException e) { throw bad("忽略的客户端品牌包含无效正则表达式"); }
                    }
                    result.add(text);
                }
                return result;
            }
            String text = text(value);
            if (type.equals("select") && !options.contains(text)) throw bad(label + "不是可用选项");
            if (key.equals("embed-color") && !text.matches("#[0-9a-fA-F]{6}")) throw bad("嵌入消息颜色须为 #RRGGBB");
            return text;
        }
    }

    static List<Field> fields(String id, YamlConfiguration yaml) {
        List<Field> fields = new ArrayList<>();
        boolean compatible = compatible(id, yaml);
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                Field field = field(id, key, yaml.get(key));
                if (!compatible) field = new Field(field.key, field.label, field.group, field.type,
                        "当前 GrimAC 配置模式或版本尚未验证，仅可查看。", true, field.secret,
                        field.min, field.max, field.step, field.options);
                fields.add(field);
            }
        }
        return List.copyOf(fields);
    }

    private static Field field(String id, String key, Object value) {
        String label = null, group = "其他配置";
        boolean version = key.equals("config-flavor") || key.equals("config-version");
        boolean backend = id.startsWith("backend-") || id.startsWith("db-") || id.startsWith("database-");
        if (version) { label = key.equals("config-flavor") ? "配置模式标记" : "配置版本"; group = "版本信息"; }
        else if (id.equals("config")) {
            label = CONFIG.get(key);
            if (label == null && key.contains(".")) {
                String check = key.substring(0, key.indexOf('.'));
                String suffix = key.substring(key.indexOf('.') + 1);
                if (CHECKS.containsKey(check)) {
                    label = checkOption(suffix); group = CHECKS.get(check);
                }
            } else group = configGroup(key);
        } else if (id.equals("messages")) { label = MESSAGES.get(key); group = key.startsWith("grim-history") ? "历史记录消息" : "游戏内消息"; }
        else if (id.equals("discord")) { label = DISCORD.get(key); group = "Discord 告警"; }
        else if (id.equals("database")) { label = DATABASE.get(key); group = databaseGroup(key); }
        else if (backend && key.contains(".")) {
            String suffix = key.substring(key.indexOf('.') + 1);
            label = BACKEND.get(suffix); group = "数据库连接与存储";
        } else if (id.equals("punishments") && key.startsWith("Punishments.")) {
            int end = key.indexOf('.', 12);
            if (end > 12) {
                String check = key.substring(12, end), suffix = key.substring(end + 1);
                group = CHECKS.getOrDefault(check, "自定义处罚分组（" + check + "）");
                label = switch (suffix) {
                    case "remove-violations-after" -> "违规重置时间（秒）";
                    case "checks" -> "匹配的检测名称";
                    case "commands" -> "处罚与告警规则";
                    default -> null;
                };
            }
        }
        boolean known = label != null;
        boolean secret = secret(key);
        String type = expectedType(id, key, known, value);
        if (secret) type = "text";
        List<String> options = options(key);
        if (!options.isEmpty()) type = "select";
        boolean supported = switch (type) {
            case "bool" -> value instanceof Boolean;
            case "number" -> value instanceof Number number && Double.isFinite(number.doubleValue());
            case "string-list", "punishment-commands" -> value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
            default -> value instanceof String || version && value instanceof Number;
        };
        Double min = null, max = null, step = null;
        if (type.equals("number")) {
            String suffix = key.substring(key.lastIndexOf('.') + 1);
            boolean integer = !Set.of("threshold", "setback-decay-multiplier", "immediate-setback-threshold",
                    "max-advantage", "max-ceiling", "decay").contains(suffix);
            step = integer ? 1d : 0.0001;
            min = 0d; max = (double) Integer.MAX_VALUE;
            if (key.endsWith(".immediate-setback-threshold") || key.endsWith(".max-advantage")) min = -1d;
            if (key.equals("update-permission-ticks") || key.equals("max-ping-out-of-flying")
                    || key.equals("max-ping-firework-boost") || key.endsWith(".ping-abuse-limit-threshold"))
                min = -1d;
            if (key.equals("max-transaction-time")) { min = 1d; max = 180d; step = 1d; }
            if (key.equals("exploit.distance-to-check-if-ghostblocks")) { min = 2d; max = 4d; step = 1d; }
            if (key.endsWith(".remove-violations-after")) { min = 0d; max = 2147483d; step = 1d; }
            if (key.endsWith(".port")) { min = 1d; max = 65535d; step = 1d; }
            if (key.endsWith(".queue-capacity")) { min = 1d; max = 1048576d; step = 1d; }
            if (key.endsWith(".minimum-idle")) min = 0d;
            if (key.endsWith(".maximum-pool-size") || key.endsWith(".batch-size") || key.endsWith(".batch-flush-cap")) { min = 1d; max = 1048576d; }
            if (key.endsWith(".setback-decay-multiplier") || key.endsWith(".decay")) { min = 0d; max = 1d; step = 0.0001; }
            if (key.endsWith(".threshold")) { min = 0d; max = 1000000d; step = 0.0001; }
        }
        boolean readOnly = version || !known || !supported;
        String help = readOnly ? version ? "由 GrimAC 维护，不能手动修改。"
                : !supported && known ? "配置值类型不兼容，保留原值。" : "当前版本尚未收录此配置项，保留原值。" : help(key, id, secret);
        return new Field(key, known ? label : "未收录配置", group, type, help, readOnly, secret, min, max, step, options);
    }

    static void validateRelations(YamlConfiguration yaml) {
        ordered(yaml, "database.write-path.batch-size", "database.write-path.queue-capacity", "写入批量不能超过队列容量");
        for (String prefix : List.of("mysql", "postgres"))
            ordered(yaml, prefix + ".pool-settings.minimum-idle", prefix + ".pool-settings.maximum-pool-size", "最少空闲连接不能超过最大连接数");
    }

    private static void ordered(YamlConfiguration yaml, String first, String second, String message) {
        Object a = yaml.get(first), b = yaml.get(second);
        if (a instanceof Number n && b instanceof Number m && n.doubleValue() > m.doubleValue()) throw bad(message);
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.length() > MAX_TEXT || text.indexOf('\u0000') >= 0)
            throw bad("文本配置必须为不超过 32768 个字符的字符串，且不能含空字符");
        return text;
    }

    private static void validateCommand(String value) {
        var match = PUNISHMENT.matcher(value);
        if (!match.matches()) throw bad("处罚规则须包含起始违规值、重复间隔及非空命令");
        try { Integer.parseInt(match.group(1)); Integer.parseInt(match.group(2)); }
        catch (NumberFormatException e) { throw bad("处罚规则的违规值与间隔不能超过 2147483647"); }
    }

    private static boolean secret(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("passwd") || lower.contains("secret")
                || lower.contains("token") || lower.equals("webhook") || lower.endsWith(".webhook") || lower.endsWith("webhook-url")
                || lower.contains("connection-string") || lower.contains("jdbc-params")
                || lower.endsWith(".url") || lower.endsWith(".uri");
    }

    private static String expectedType(String id, String key, boolean known, Object value) {
        if (!known) return value instanceof Boolean ? "bool" : value instanceof Number ? "number"
                : value instanceof List<?> ? "string-list" : "text";
        String leaf = key.substring(key.lastIndexOf('.') + 1);
        if (key.equals("config-flavor") || key.equals("config-version")) return "text";
        if (id.equals("messages")) return key.equals("profile") || key.equals("help") ? "string-list" : "textarea";
        if (id.equals("discord")) return switch (key) {
            case "enabled", "disable-webhook-validation", "include-timestamp" -> "bool";
            case "violation-content" -> "string-list"; default -> "text";
        };
        if (id.equals("punishments")) return switch (leaf) {
            case "checks" -> "string-list"; case "commands" -> "punishment-commands"; default -> "number";
        };
        if (id.equals("config")) {
            if (key.equals("client-brand.ignored-clients") || key.equals("spectators.allowed-worlds")) return "string-list";
            if (key.startsWith("exploit.")) return leaf.equals("distance-to-check-if-ghostblocks") ? "number" : "bool";
            String prefix = key.contains(".") ? key.substring(0, key.indexOf('.')) : "";
            if (CHECKS.containsKey(prefix)) return Set.of("exempt-placing-while-digging", "block-impossible-hits", "enable-post-packet")
                    .contains(leaf) ? "bool" : "number";
            return Set.of("max-transaction-time", "packet-spam-threshold", "max-ping-out-of-flying",
                    "max-ping-firework-boost", "update-permission-ticks").contains(key) ? "number" : "bool";
        }
        if (id.equals("database")) {
            if (leaf.equals("enabled") || leaf.equals("scope-per-server") || key.equals("database.migration.skip")) return "bool";
            if (key.startsWith("database.routing.") || key.equals("database.server-name") || leaf.equals("wait-strategy")) return "text";
            if (key.equals("database.name-resolution.chain")) return "string-list";
            return "number";
        }
        if (id.startsWith("database-") || id.startsWith("backend-") || id.startsWith("db-")) {
            if (leaf.equals("warn-on-history")) return "bool";
            if (key.contains(".pool-settings.") || Set.of("port", "batch-flush-cap", "busy-timeout-ms", "cache-pages", "timeout-ms").contains(leaf)
                    || key.equals("redis.database")) return "number";
            return "text";
        }
        return "text";
    }

    private static List<String> options(String key) {
        if (key.equals("database.write-path.wait-strategy")) return WAIT_STRATEGIES.stream().sorted().toList();
        if (key.endsWith(".journal-mode")) return List.of("DELETE", "TRUNCATE", "PERSIST", "MEMORY", "WAL", "OFF");
        if (key.endsWith(".synchronous-mode")) return List.of("OFF", "NORMAL", "FULL", "EXTRA");
        return List.of();
    }

    private static boolean compatible(String id, YamlConfiguration yaml) {
        Object flavor = yaml.get("config-flavor");
        if (flavor != null && !"V2".equals(flavor)) return false;
        Object version = yaml.get("config-version");
        if (version == null) return true;
        int expected = switch (id) {
            case "config" -> 11; case "messages", "database-mysql", "database-postgres" -> 2; default -> 1;
        };
        return version instanceof Number number && number.doubleValue() == expected;
    }

    private static String optionLabel(String value) {
        return switch (value) {
            case "BLOCKING" -> "阻塞等待"; case "TIMEOUT_BLOCKING" -> "超时阻塞等待"; case "SLEEPING" -> "休眠等待";
            case "YIELDING" -> "让出线程"; case "BUSY_SPIN" -> "忙碌自旋"; case "NORMAL" -> "常规同步";
            case "FULL" -> "完整同步"; case "EXTRA" -> "额外同步"; case "OFF" -> "关闭";
            case "WAL" -> "预写式日志"; case "DELETE" -> "删除日志"; case "TRUNCATE" -> "截断日志";
            case "PERSIST" -> "保留日志"; case "MEMORY" -> "内存日志"; default -> value;
        };
    }

    private static String configGroup(String key) {
        if (key.startsWith("alerts") || key.startsWith("verbose")) return "告警与输出";
        if (key.startsWith("client-brand")) return "客户端品牌";
        if (key.startsWith("spectators")) return "观察模式";
        if (key.startsWith("exploit")) return "漏洞兼容";
        if (key.startsWith("reset-item")) return "物品使用状态";
        return "连接与检测";
    }

    private static String databaseGroup(String key) {
        if (key.startsWith("database.routing")) return "数据存储路由";
        if (key.startsWith("database.write-path")) return "异步写入";
        if (key.startsWith("database.retention")) return "数据保留";
        if (key.startsWith("database.session")) return "会话记录";
        if (key.startsWith("database.migration")) return "旧数据迁移";
        return "历史记录";
    }

    private static String checkOption(String key) {
        return switch (key) {
            case "setback-decay-multiplier" -> "累计优势衰减倍率"; case "threshold" -> "检测容差（格）";
            case "immediate-setback-threshold" -> "立即拉回阈值（-1 关闭）"; case "max-advantage" -> "累计优势拉回阈值（-1 关闭）";
            case "max-ceiling" -> "累计优势封顶"; case "setback-violation-threshold", "setbackvl" -> "拉回所需违规值";
            case "cancelvl" -> "取消操作所需违规值"; case "decay" -> "违规衰减量"; case "drift" -> "允许时间偏差（毫秒）";
            case "ping-abuse-limit-threshold" -> "延迟限制阈值（毫秒，-1 关闭）";
            case "exempt-placing-while-digging" -> "挖掘时放置方块豁免";
            case "block-impossible-hits" -> "取消不可能命中的攻击";
            case "enable-post-packet" -> "每刻发送额外检测数据包"; default -> null;
        };
    }

    private static String help(String key, String id, boolean secret) {
        if (secret) return "已配置的敏感值不会显示；不修改则保留原值，清空并保存会移除原值。";
        if (key.equals("check-for-updates")) return "下次启动 GrimAC 时应用。";
        if (key.equals("experimental-chunk-sharing")) return "以写时复制方式共享区块分区，可减少内存使用；属于实验性功能。";
        if (key.equals("client-brand.ignored-clients")) return "按顺序匹配客户端品牌的正则表达式；每项必须是有效正则。";
        if (key.equals("update-permission-ticks")) return "-1 关闭定期刷新；正整数表示刷新间隔（游戏刻）。";
        if (key.endsWith(".commands")) return "格式为 起始违规值:重复间隔 命令；间隔 0 表示只执行一次，保留 GrimAC 占位符。";
        if (key.endsWith(".checks")) return "GrimAC 检测名称；前缀 ! 可排除检测，列表顺序保持不变。";
        if (key.equals("database.write-path.queue-capacity")) return "必须为正的二次幂；较大队列会增加内存占用。";
        if (key.equals("database.session.heartbeat-interval-ms")) return "在线会话心跳间隔，0 表示关闭。";
        if (key.equals("database.migration.max-duration-ms")) return "旧数据迁移超时，0 表示不限制。";
        if (id.equals("messages")) return "保留原有占位符与 MiniMessage 标签，保存后重载 GrimAC 应用。";
        return "保存到 GrimAC 配置文件，重载 GrimAC 后应用。";
    }

    private static Map<String, String> configLabels() {
        return Map.ofEntries(
                Map.entry("alerts.print-to-console", "向控制台输出告警"), Map.entry("alerts.proxy.send", "向代理子服发送告警"),
                Map.entry("alerts.proxy.receive", "接收代理子服告警"), Map.entry("verbose.print-to-console", "向控制台输出详细告警"),
                Map.entry("check-for-updates", "检查 GrimAC 更新"), Map.entry("client-brand.ignored-clients", "忽略的客户端品牌"),
                Map.entry("client-brand.disconnect-blacklisted-forge-versions", "拒绝有攻击距离漏洞的 Forge 版本"),
                Map.entry("spectators.hide-regardless", "始终隐藏有观察权限的玩家"), Map.entry("spectators.allowed-worlds", "观察者保持可见的世界"),
                Map.entry("max-transaction-time", "交互数据包超时（秒）"), Map.entry("disable-pong-cancelling", "禁用 Pong 数据包取消"),
                Map.entry("disable-default-resync-handler", "禁用默认方块重新同步"), Map.entry("cancel-duplicate-packet", "取消重复移动数据包"),
                Map.entry("ignore-duplicate-packet-rotation", "忽略重复数据包中的视角变化"),
                Map.entry("exploit.allow-sprint-jumping-when-using-elytra", "允许鞘翅使用时疾跑跳跃"),
                Map.entry("exploit.allow-building-on-ghostblocks", "允许在假方块上继续搭建"),
                Map.entry("exploit.distance-to-check-if-ghostblocks", "假方块检查距离（格）"),
                Map.entry("debug-pipeline-on-join", "加入时输出网络管道调试"), Map.entry("experimental-checks", "启用实验性检测"),
                Map.entry("experimental-chunk-sharing", "实验性区块内存共享"),
                Map.entry("reset-item-usage-on-item-update", "物品更新时重置使用状态"), Map.entry("reset-item-usage-on-attack", "攻击时重置使用状态"),
                Map.entry("reset-item-usage-on-slot-change", "切换物品槽时重置使用状态"), Map.entry("reset-item-usage-on-item-use", "使用物品时重置使用状态"),
                Map.entry("packet-spam-threshold", "每秒取消数据包踢出阈值"), Map.entry("debug-packet-cancel", "数据包超限时输出堆栈"),
                Map.entry("max-ping-out-of-flying", "退出飞行的延迟上限（毫秒，-1 关闭）"),
                Map.entry("max-ping-firework-boost", "烟花加速的延迟上限（毫秒，-1 关闭）"),
                Map.entry("update-permission-ticks", "定期权限刷新间隔（刻）"));
    }

    private static Map<String, String> messageLabels() {
        return Map.ofEntries(
                Map.entry("prefix", "消息前缀"), Map.entry("alerts-enabled", "告警已开启"), Map.entry("alerts-disabled", "告警已关闭"),
                Map.entry("verbose-enabled", "详细告警已开启"), Map.entry("verbose-disabled", "详细告警已关闭"),
                Map.entry("brands-enabled", "客户端品牌显示已开启"), Map.entry("brands-disabled", "客户端品牌显示已关闭"),
                Map.entry("client-brand-format", "客户端品牌消息格式"), Map.entry("console-specify-target", "控制台缺少目标玩家"),
                Map.entry("reloading", "正在重载配置"), Map.entry("reloaded", "配置重载完成"), Map.entry("reload-failed", "配置重载失败"),
                Map.entry("player-not-found", "玩家不存在或离线"), Map.entry("player-not-this-server", "玩家不在当前服务器"),
                Map.entry("sender-not-found", "执行者被豁免"), Map.entry("spectate-return", "返回观察前位置"),
                Map.entry("cannot-spectate-return", "无法返回观察位置"), Map.entry("cannot-run-on-self", "不能对自己执行"),
                Map.entry("upload-log", "日志上传完成"), Map.entry("upload-log-start", "日志上传中"),
                Map.entry("upload-log-not-found", "日志不存在"), Map.entry("upload-log-upload-failure", "日志上传失败"),
                Map.entry("disconnect.timeout", "连接超时提示"), Map.entry("disconnect.closed", "连接关闭提示"),
                Map.entry("disconnect.error", "数据包处理错误提示"), Map.entry("disconnect.blacklisted-forge", "被禁止的 Forge 版本提示"),
                Map.entry("run-as-player", "仅允许玩家执行"), Map.entry("run-as-player-or-console", "仅允许玩家或控制台执行"),
                Map.entry("webhook-not-enabled", "Webhook 未开启"), Map.entry("webhook-test-message", "Webhook 测试内容"),
                Map.entry("webhook-test-succeeded", "Webhook 测试成功"), Map.entry("webhook-test-failed", "Webhook 测试失败"),
                Map.entry("alerts-format", "违规告警格式"), Map.entry("alerts-format-proxy", "跨服违规告警格式"),
                Map.entry("experimental-symbol", "实验性检测标记"), Map.entry("profile", "玩家档案消息"), Map.entry("help", "命令帮助消息"),
                Map.entry("grim-history-load-failure", "历史系统加载失败"), Map.entry("grim-history-disabled", "历史系统已关闭"),
                Map.entry("grim-history-failed", "历史查询失败"), Map.entry("grim-history-unknown-player", "历史中的未知玩家"),
                Map.entry("grim-history-no-sessions", "没有会话历史"), Map.entry("grim-history-session-not-found", "未找到历史会话"),
                Map.entry("grim-history-header", "历史会话列表标题"), Map.entry("grim-history-session", "历史会话摘要"),
                Map.entry("grim-history-crashed-marker", "异常结束会话标记"), Map.entry("grim-history-detail-header", "会话详情标题"),
                Map.entry("grim-history-detail-meta1", "会话服务器信息"), Map.entry("grim-history-detail-meta2", "会话客户端信息"),
                Map.entry("grim-history-detail-violations-header", "会话违规统计标题"), Map.entry("grim-history-detail-empty", "无违规内容"),
                Map.entry("grim-history-detail-group", "违规分组格式"), Map.entry("grim-history-detail-group-hover", "违规分组悬停内容"),
                Map.entry("grim-history-detail-group-hover-entry", "分组违规悬停条目"), Map.entry("grim-history-hover-description", "检测说明悬停片段"),
                Map.entry("grim-history-detail-group-hover-verbose", "分组详情悬停片段"), Map.entry("grim-history-detail-entry", "单条违规格式"),
                Map.entry("grim-history-detail-entry-hover", "单条违规悬停内容"), Map.entry("grim-history-detail-entry-hover-verbose", "单条违规详情悬停片段"),
                Map.entry("grim-history-check-count", "检测次数格式"));
    }

    private static Map<String, String> databaseLabels() {
        return Map.ofEntries(
                Map.entry("database.enabled", "保存违规历史"), Map.entry("database.routing.violation", "违规记录存储后端"),
                Map.entry("database.routing.session", "会话存储后端"), Map.entry("database.routing.player-identity", "玩家身份存储后端"),
                Map.entry("database.routing.setting", "设置存储后端"), Map.entry("database.routing.blob", "日志内容存储后端"),
                Map.entry("database.session.gap-ms", "迁移会话合并间隔（毫秒）"), Map.entry("database.session.scope-per-server", "按服务器区分会话"),
                Map.entry("database.session.heartbeat-interval-ms", "会话心跳间隔（毫秒）"),
                Map.entry("database.write-path.queue-capacity", "写入队列容量"), Map.entry("database.write-path.batch-size", "写入批量大小"),
                Map.entry("database.write-path.flush-interval-ms", "写入刷新间隔（毫秒）"), Map.entry("database.write-path.warn-rate-ms", "写入告警间隔（毫秒）"),
                Map.entry("database.write-path.shutdown-drain-timeout-ms", "关闭时队列排空超时（毫秒）"),
                Map.entry("database.write-path.wait-strategy", "写入线程等待策略"),
                Map.entry("database.retention.session.enabled", "清理过期会话"), Map.entry("database.retention.session.max-age-days", "会话保留天数"),
                Map.entry("database.retention.violation.enabled", "清理过期违规"), Map.entry("database.retention.violation.max-age-days", "违规保留天数"),
                Map.entry("database.retention.player-identity.enabled", "清理玩家身份"), Map.entry("database.retention.player-identity.max-age-days", "玩家身份保留天数"),
                Map.entry("database.retention.setting.enabled", "清理历史设置"), Map.entry("database.retention.setting.max-age-days", "历史设置保留天数"),
                Map.entry("database.retention.blob.enabled", "清理过期日志内容"), Map.entry("database.retention.blob.max-age-days", "日志内容保留天数"),
                Map.entry("database.migration.skip", "跳过旧版数据迁移"), Map.entry("database.migration.max-duration-ms", "迁移超时（毫秒）"),
                Map.entry("database.name-resolution.chain", "玩家名称解析顺序"), Map.entry("database.history.entries-per-page", "每页历史记录数"),
                Map.entry("database.history.group-interval-ms", "历史违规分组间隔（毫秒）"), Map.entry("database.server-name", "会话中的服务器名称"));
    }

    private static Map<String, String> backendLabels() {
        return Map.ofEntries(
                Map.entry("host", "数据库主机"), Map.entry("port", "数据库端口"), Map.entry("database", "数据库名称或编号"),
                Map.entry("user", "数据库用户名"), Map.entry("password", "数据库密码"), Map.entry("connection-string", "数据库连接字符串"),
                Map.entry("extra-jdbc-params", "额外 JDBC 连接参数"), Map.entry("path", "数据库文件路径"),
                Map.entry("journal-mode", "日志模式"), Map.entry("synchronous-mode", "磁盘同步模式"),
                Map.entry("busy-timeout-ms", "数据库锁等待超时（毫秒）"), Map.entry("cache-pages", "数据库页缓存数量"),
                Map.entry("batch-flush-cap", "单次写入批量上限"), Map.entry("timeout-ms", "连接超时（毫秒）"),
                Map.entry("key-prefix", "数据库键前缀"), Map.entry("warn-on-history", "历史存储持久化提醒"),
                Map.entry("pool-settings.maximum-pool-size", "最大数据库连接数"), Map.entry("pool-settings.minimum-idle", "最少空闲连接数"),
                Map.entry("pool-settings.maximum-lifetime-ms", "连接最长寿命（毫秒）"), Map.entry("pool-settings.keepalive-time-ms", "空闲连接保活间隔（毫秒）"),
                Map.entry("pool-settings.connection-timeout-ms", "获取连接超时（毫秒）"),
                Map.entry("tables.meta", "元信息表名"), Map.entry("tables.checks", "检测信息表名"), Map.entry("tables.players", "玩家身份表名"),
                Map.entry("tables.sessions", "会话记录表名"), Map.entry("tables.violations", "违规记录表名"), Map.entry("tables.settings", "设置记录表名"));
    }

    private static HttpException bad(String message) { return HttpException.badRequest(message); }
}
