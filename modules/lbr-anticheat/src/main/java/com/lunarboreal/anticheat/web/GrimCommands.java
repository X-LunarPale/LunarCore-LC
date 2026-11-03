package com.lunarboreal.anticheat.web;

import com.lunarboreal.api.web.HttpException;
import java.util.*;

/** Fixed catalog verified against the Cloud builders in GrimAC 2.3.75-b08e9b1. */
public final class GrimCommands {
    public static final String ARTIFACT = "grimac-bukkit-2.3.75-b08e9b1.jar";
    public record Arg(String name, String label, String type, boolean required, List<String> options, int min, int max) {}
    public record Op(String id, String label, String command, String permission, String grimPermission,
                     boolean supported, String reason, List<Arg> args) {}
    private static Arg arg(String name, String type, boolean required) { return new Arg(name, name, type, required, List.of(), 1, Integer.MAX_VALUE); }
    private static Op op(String id, String label, String command, String grim, boolean manage, Arg... args) {
        return new Op(id, label, command, manage ? "anticheat.manage" : "anticheat.view", grim, true, "", List.of(args));
    }
    private static Op unavailable(String id, String label, String grim, String reason, Arg... args) {
        return new Op(id, label, "grim " + (id.equals("history-copy") ? "history copy" : id), "anticheat.manage", grim, false, reason, List.of(args));
    }
    public static final List<Op> CATALOG = List.of(
        op("help", "命令帮助", "grim help", "grim.help", false),
        op("version", "版本信息", "grim version", "grim.version", false),
        op("perf", "检测性能", "grim perf", "grim.performance", false),
        op("profile", "玩家检测档案", "grim profile", "grim.profile", false, arg("target", "player", true)),
        op("list", "玩家列表或详情", "grim list", "grim.list", false, arg("target", "playerOrPlayers", true)),
        op("history", "历史会话", "grim history", "grim.history", false, arg("target", "player", true), arg("page", "int", false), arg("name", "filter", false), arg("match", "filter", false), arg("grep", "filter", false)),
        op("history-session", "历史会话详情", "grim history", "grim.history", false, arg("target", "player", true), arg("session", "session", true), arg("page", "int", false), arg("detailed", "bool", false), arg("verbose", "bool", false), arg("name", "filter", false), arg("match", "filter", false), arg("grep", "filter", false)),
        op("history-session-help", "历史会话用法", "grim history", "grim.history", false, arg("target", "player", true)),
        op("history-player", "历史玩家分支", "grim history player", "grim.history", false, arg("target", "player", true), arg("session", "session", false), arg("page", "int", false), arg("detailed", "bool", false), arg("verbose", "bool", false), arg("name", "filter", false), arg("match", "filter", false), arg("grep", "filter", false)),
        op("reload", "重载 GrimAC 配置", "grim reload", "grim.reload", true),
        op("debug", "切换玩家控制台调试", "grim consoledebug", "grim.consoledebug", true, arg("target", "player", true)),
        op("consoledebug", "切换玩家控制台调试输出", "grim consoledebug", "grim.consoledebug", true, arg("target", "player", true)),
        op("dump", "生成并上传诊断报告", "grim dump", "grim.dump", true),
        op("log", "上传违规调试日志", "grim log", "grim.log", true, arg("flagId", "int", true)),
        op("sendalert", "发送管理员告警", "grim sendalert", "grim.sendalert", true, arg("message", "message", true)),
        op("testwebhook", "测试 Webhook", "grim testwebhook", "grim.testwebhook", true),
        op("history-repair", "修复历史检测标识", "grim history repair check-ids", "grim.history.repair", true),
        op("history-migrate", "迁移旧版历史数据", "grim history migrate", "grim.history.migrate", true, arg("delete", "bool", false)),
        unavailable("history-copy", "复制历史存储", "grim.history.copy", "此构建的 DataStoreLifecycle.allBackendsForCommands() 固定返回空映射，命令无法选择有效来源或目标。"),
        unavailable("alerts", "游戏内告警订阅", "grim.alerts", "此命令切换玩家订阅或全局控制台告警；网页不代替玩家，也不更改共享控制台订阅。请使用近期违规记录。"),
        unavailable("brands", "游戏内客户端品牌订阅", "grim.brand", "此功能属于游戏内发送者订阅，网页无法接收该玩家的消息。"),
        unavailable("verbose", "游戏内详细告警订阅", "grim.verbose", "此功能属于游戏内发送者订阅，网页不更改共享控制台状态。"),
        unavailable("spectate", "观察玩家", "grim.spectate", "GrimAC 要求游戏内玩家执行。", arg("target", "player", true)),
        unavailable("stopspectating", "退出观察", "grim.spectate", "GrimAC 要求游戏内玩家执行。", new Arg("here", "here", "enum", false, List.of("here"), 0, 0))
    );
    public static Op find(String id) { return CATALOG.stream().filter(o -> o.id.equals(id)).findFirst().orElseThrow(() -> HttpException.badRequest("未知操作")); }
    public static String build(Op op, Map<String, Object> values) {
        if (!op.supported) throw HttpException.badRequest(op.reason);
        if (values.keySet().stream().anyMatch(k -> op.args.stream().noneMatch(a -> a.name.equals(k)))) throw HttpException.badRequest("存在未声明的参数");
        Map<String, String> valid = new LinkedHashMap<>();
        for (Arg a : op.args) {
            Object raw = values.get(a.name);
            if (raw == null || raw instanceof String s && s.isEmpty()) { if (a.required) throw HttpException.badRequest("缺少参数：" + a.name); continue; }
            String s = String.valueOf(raw);
            boolean ok = switch (a.type) {
                case "bool" -> raw instanceof Boolean;
                case "int" -> raw instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == Math.rint(n.doubleValue()) && n.doubleValue() >= a.min && n.doubleValue() <= a.max;
                case "enum" -> raw instanceof String && a.options.contains(s);
                case "player" -> raw instanceof String && s.matches("[A-Za-z0-9_]{1,16}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
                case "playerOrPlayers" -> raw instanceof String && (s.equals("players") || s.matches("[A-Za-z0-9_]{1,16}|[0-9a-fA-F-]{36}"));
                case "session" -> raw instanceof String && s.matches("latest|last|l|[1-9][0-9]{0,8}");
                case "filter" -> raw instanceof String && s.length() <= 128 && s.matches("[A-Za-z0-9_.*^$|?+()\\[\\]{}:,=!-]+") && !s.startsWith("-");
                case "message" -> raw instanceof String && s.length() <= 500 && s.codePoints().noneMatch(c -> Character.isISOControl(c) || c == '\u2028' || c == '\u2029');
                default -> false;
            };
            if (!ok) throw HttpException.badRequest("参数无效：" + a.name);
            valid.put(a.name, a.type.equals("int") ? Integer.toString(((Number) raw).intValue()) : s);
        }
        StringBuilder out = new StringBuilder(op.command);
        for (String key : List.of("target", "src", "dst", "flagId", "message")) if (valid.containsKey(key)) out.append(' ').append(valid.get(key));
        if (op.id.equals("history-session-help")) out.append(" session");
        if (valid.containsKey("session")) out.append(" session ").append(valid.get("session"));
        if (valid.containsKey("page")) out.append(" page ").append(valid.get("page"));
        for (String key : List.of("delete", "detailed", "verbose")) if ("true".equals(valid.get(key))) out.append(" --").append(key);
        for (String key : List.of("name", "match", "grep")) if (valid.containsKey(key)) out.append(" --").append(key).append(' ').append(valid.get(key));
        if (op.id.equals("history-copy") && Objects.equals(valid.get("src"), valid.get("dst"))) throw HttpException.badRequest("来源和目标存储必须不同");
        if (op.id.equals("history-player") && !valid.containsKey("session") && (valid.containsKey("detailed") || valid.containsKey("verbose"))) throw HttpException.badRequest("详细输出需要指定会话");
        return out.toString();
    }
    private GrimCommands() {}
}
