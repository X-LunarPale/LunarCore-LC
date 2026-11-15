"""Read-only Edge smoke checks for module workspaces with isolated API fixtures."""
import json
import os
import base64
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


BASE = os.environ.get("LC_UI_BASE", "http://127.0.0.1:5216").rstrip("/")
OUTPUT = Path(__file__).resolve().parents[1] / "artifacts" / "maintenance-review-20261115" / "extensions"
EDGE = "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
NOW = 1794729600000


def field(key, label, kind="text", **metadata):
    return {"key": key, "label": label, "type": kind, **metadata}


def table(title, endpoint, columns, **metadata):
    return {"type": "table", "title": title, "endpoint": endpoint,
            "columns": [field(*column) for column in columns], "refreshSeconds": 0, **metadata}


def form(title, endpoint, fields):
    return {"type": "form", "title": title, "loadEndpoint": endpoint,
            "saveEndpoint": endpoint, "fields": fields}


def panel(namespace, identifier, title, sections):
    return {"namespace": namespace, "id": identifier, "title": title, "group": "管理",
            "icon": "settings", "permission": f"{namespace}.view", "sections": sections}


def page_data(items):
    return {"items": items, "total": len(items)}


BAN_FIELDS = [
    field("server-name", "服务器名"), field("appeal-url", "申诉地址"),
    field("date-format", "日期格式"), field("default-reason", "默认原因"),
    field("broadcast.enabled", "广播处罚消息", "bool"),
    field("broadcast.api", "广播 API 处罚", "bool"),
    field("exempt-enabled", "启用豁免权限", "bool"),
    field("warn.expire", "警告有效期", "duration"),
    field("warn.actions", "警告累计动作（阈值:命令，每行一条）", "textarea"),
    field("warn.reset-after-action", "执行动作后重置警告", "bool"),
    field("muted-commands", "禁言时禁止的命令（每行一条）", "textarea"),
    field("duration-limits.enabled", "启用处罚时长限制", "bool"),
    field("duration-limits.default", "默认时长上限", "duration"),
    field("reasons", "快捷原因（YAML）", "code"),
]
XRAY_FIELDS = [
    field("fake-ore.enabled", "启用方案二：假矿伪装", "bool"),
    field("fake-ore.max-y", "假矿：最大高度", "number"),
    field("fake-ore.radius-chunks", "假矿：处理半径（区块，0=跟随视距）", "number"),
    field("fake-ore.chunks-per-tick", "假矿：每 2 tick 最多计算区块数", "number"),
    field("fake-ore.packets-per-tick", "假矿：每玩家每 2 tick 最多发包数", "number"),
    field("fake-ore.worlds", "假矿：启用世界（每行一个，留空=全部）", "textarea"),
    field("fake-ore.ores", "假矿：伪装的矿石（每行一个）", "textarea"),
    field("detection.enabled", "启用方案三：挖矿检测", "bool"),
    field("detection.window-minutes", "检测窗口（分钟）", "number"),
    field("detection.dense.threshold", "密集告警：窗口内矿脉加权数阈值", "number"),
    field("detection.ratio.min-veins", "比率告警：最少矿脉数", "number"),
    field("detection.ratio.max-stone-per-vein", "比率告警：每条矿脉石头数低于此值", "number"),
    field("detection.direct.enabled", "启用直线命中检测", "bool"),
    field("detection.direct.threshold", "直线命中：窗口内次数阈值", "number"),
    field("detection.direct.lookback-blocks", "直线命中：回看挖掘方块数", "number"),
    field("detection.direct.min-start-distance", "直线命中：路径起点最小距离", "number"),
    field("detection.alert-cooldown-seconds", "同类告警冷却（秒）", "number"),
    field("detection.rare-ores", "稀有矿及权重（YAML）", "code"),
    field("detection.common-blocks", "普通方块（每行一个）", "textarea"),
    field("actions.enabled", "告警时自动执行命令", "bool"),
    field("actions.min-alerts", "本次在线累计告警数达到多少才执行", "number"),
    field("actions.types", "触发的告警类型（DIRECT/DENSE/RATIO，每行一个，留空=全部）", "textarea"),
    field("actions.commands", "执行的命令（每行一条，占位符 {player} {uuid} {type} {detail} {alerts}）", "textarea"),
]
PANELS = [
    panel("chat", "ai", "AI 聊天", []),
    panel("qqauth", "accounts", "QQ验证与账号认证", [
        {"type": "stats", "title": "概览", "endpoint": "stats"},
        table("游戏认证账号", "accounts", [("name", "玩家", "player")], rowActions=[
            {"id": identifier, "label": label, "endpoint": f"accounts/{{uuid}}/{identifier}",
             "permission": "qqauth.manage"}
            for identifier, label in [("lock", "锁定"), ("unlock", "解锁"), ("reset", "重置密码"),
                                      ("forceunbind", "强制解绑"), ("delete", "删除账号")]
        ]),
        {"type": "text", "title": "配置与迁移", "markdown": "QQAuth 桥接配置。"},
    ]),
    panel("terrain", "tectonic", "Tectonic 宏伟地形", []),
    panel("multiverse", "worlds", "多世界管理", []),
    panel("ban", "ban", "封禁管理", [
        {"type": "stats", "title": "概览", "endpoint": "stats"},
        *[table(title, endpoint, [
            ("id", "ID", "number"), ("typeLabel", "类型", "tag"), ("name", "玩家", "player"),
            ("ip", "IP"), ("reason", "原因"), ("operator", "操作者"),
            ("created", "时间", "datetime"), ("expires", "到期", "datetime"), ("status", "状态", "tag"),
        ]) for title, endpoint in [("生效中的处罚", "punishments/active"), ("全部处罚记录", "punishments/all")]],
        form("配置", "config", BAN_FIELDS),
    ]),
    panel("anticheat", "anticheat", "反作弊", [
        {"type": "stats", "title": "概览", "endpoint": "stats"},
        table("近期违规记录", "violations", [
            ("time", "时间", "datetime"), ("player", "玩家", "player"),
            ("check", "检测", "tag"), ("vl", "违规等级", "number"),
            ("detail", "详情"), ("ping", "延迟（毫秒）", "number"), ("world", "世界"),
        ]),
        form("桥接设置", "settings", [
            field("bridge.capture-enabled", "采集近期违规记录", "bool"),
            field("bridge.recent-capacity", "近期记录上限", "number"),
        ]),
    ]),
    panel("antixray", "antixray", "反矿透", [
        {"type": "stats", "title": "概览", "endpoint": "stats", "refreshSeconds": 30},
        table("可疑玩家排行", "suspects", [
            ("name", "玩家", "player"), ("score", "可疑分", "number"), ("alerts", "告警数", "number"),
            ("perThousand", "每千石头稀有矿"), ("rare", "稀有矿", "number"), ("stone", "普通方块", "number"),
            ("lastAlert", "最近告警", "datetime"), ("online", "在线", "bool"),
        ], rowKey="uuid"),
        table("告警记录", "alerts", [
            ("id", "ID", "number"), ("created", "时间", "datetime"), ("name", "玩家", "player"),
            ("typeLabel", "类型", "tag"), ("detail", "详情"), ("location", "位置"),
        ]),
        table("Paper anti-xray 设置（修改后需重启服务器生效）", "paper", [
            ("target", "目标"), ("enabled", "启用", "bool"), ("engine", "引擎"),
            ("maxHeight", "最大高度"), ("updateRadius", "更新半径"), ("hidden", "隐藏方块数", "number"),
            ("inherited", "继承默认的键"), ("file", "文件"),
        ], rowKey="target", searchable=False),
        form("配置", "config", XRAY_FIELDS),
    ]),
]

AI = {
    "enabled": True, "name": "月灵", "persona": "以简短自然的语气与玩家聊天。",
    "baseUrl": "https://provider.example/v1", "model": "fixture-chat", "providerType": "custom",
    "fallbackModel": "fixture-fallback", "keyConfigured": True,
    "bots": [{"id": "nebula", "enabled": True, "name": "星云", "persona": "为玩家提供生存建议。",
              "providerType": "custom", "baseUrl": "https://provider.example/v1",
              "model": "fixture-chat", "fallbackModel": "", "keyConfigured": True}],
    "replyMode": "all", "proactiveProbability": 25, "cooldownSeconds": 20,
    "memoryEnabled": True, "embeddingBaseUrl": "https://provider.example/v1",
    "embeddingModel": "fixture-embedding", "embeddingKeyConfigured": True,
    "replyOnError": True, "errorMessage": "暂时无法回复，请稍后再试。",
}
QQ_VALUES = {
    "integration.mode": "astrbot", "integration.group-id": "12345678",
    "integration.astrbot.bind-address": "0.0.0.0", "integration.astrbot.port": 25586,
    "integration.astrbot.token": "", "integration.onebot.endpoint": "ws://127.0.0.1:3001",
    "integration.onebot.token": "", "integration.onebot.reconnect-seconds": 10,
    "binding.code-length": 6, "binding.bind-ttl-seconds": 600,
    "binding.unbind-ttl-seconds": 600, "binding.reset-ttl-seconds": 600,
    "binding.max-pending-codes": 1000, "binding.max-per-qq": 3,
    "binding.required": False, "binding.reminder.enabled": True,
    "binding.reminder.timezone": "Asia/Shanghai", "binding.per-qq-limits": {"12345678": 3},
    "auth.identity-mode": "auto", "auth.min-password-length": 8, "auth.max-password-length": 64,
    "auth.bcrypt-cost": 10, "auth.max-attempts": 5, "auth.attempt-window-seconds": 60,
    "auth.max-attempt-entries": 10000, "auth.max-accounts-per-ip": 3,
    "auth.sessions.enabled": True, "auth.sessions.ttl-seconds": 3600,
    "auth.sessions.max-sessions": 1000, "auth.restrictions.allow-commands": ["help"],
}
TERRAIN_CONFIG = {
    "height": 640, "verticalScale": 1.8, "horizontalScale": 1.2,
    "rollingHills": True, "undergroundRivers": True, "lavaTunnels": True, "junglePillars": True,
    "advanced": {"global_terrain.min_y": -64, "general.snow_start_offset": 0},
}
TERRAIN = {
    "supported": True, "gameVersion": "26.3",
    "upstream": {"name": "Tectonic", "version": "3.0.29", "license": "MIT"},
    "defaults": TERRAIN_CONFIG,
    "presets": [{"id": "alpine", "name": "宏伟山脉", "config": TERRAIN_CONFIG}],
    "worlds": [{"name": "mountain", "key": "lc_terrain:mountain", "seed": "123456789",
                "config": TERRAIN_CONFIG, "autoLoad": True, "loaded": True, "players": 0,
                "minY": -64, "maxY": 576, "status": "ready", "error": "",
                "spawn": {"x": 0, "y": 160, "z": 0}, "deletable": True,
                "multiverseRegistered": True, "multiverseError": ""}],
    "busy": False, "error": "", "heights": [384, 640, 1024],
    "heightRanges": [{"minY": -64, "maxY": height - 64, "height": height} for height in [384, 640, 1024]],
    "schema": [
        {"key": "global_terrain.min_y", "type": "number", "group": "全局地形", "label": "最低高度",
         "min": -2032, "max": 0, "step": 16, "defaultValue": -64},
        {"key": "general.snow_start_offset", "type": "number", "group": "常规地形", "label": "积雪起始偏移",
         "min": -256, "max": 256, "step": 1, "defaultValue": 0},
    ],
}
GRIM_CONFIG = {
    "id": "config", "name": "config.yml", "label": "主配置", "fingerprint": "fixture-config-revision",
    "fields": [
        field("alerts.print-to-console", "控制台违规告警", "bool", group="告警与通知"),
        field("max-transaction-time", "事务超时（秒）", "number", group="网络与兼容", min=1, max=180, step=1),
    ],
    "values": {"alerts.print-to-console": True, "max-transaction-time": 60},
    "reloadRequired": True, "restartRequired": False,
}
GRIM_PUNISHMENTS = {
    "id": "punishments", "name": "punishments.yml", "label": "处罚规则",
    "fingerprint": "fixture-punishments-revision",
    "fields": [
        field("Punishments.Reach.remove-violations-after", "违规过期时间（秒）", "number", group="攻击检测", min=0, step=1),
        field("Punishments.Reach.checks", "检测项", "string-list", group="攻击检测"),
        field("Punishments.Reach.commands", "处罚命令", "punishment-commands", group="攻击检测"),
    ],
    "values": {"Punishments.Reach.remove-violations-after": 300,
               "Punishments.Reach.checks": ["Reach"],
               "Punishments.Reach.commands": ["20:40 [alert]", "100:0 lc ban %player% 1h Reach"]},
    "reloadRequired": True, "restartRequired": False,
}
PUNISHMENT = {"id": 1, "typeLabel": "封禁", "name": "Steve", "ip": "", "reason": "Reach",
              "operator": "反作弊", "created": NOW, "expires": NOW + 3600000, "status": "生效中"}
XRAY_CONFIG = {
    "fake-ore.enabled": False, "fake-ore.max-y": 64, "fake-ore.radius-chunks": 4,
    "fake-ore.chunks-per-tick": 2, "fake-ore.packets-per-tick": 256,
    "fake-ore.worlds": "world", "fake-ore.ores": "DIAMOND_ORE\nDEEPSLATE_DIAMOND_ORE",
    "detection.enabled": True, "detection.window-minutes": 30, "detection.dense.threshold": 30,
    "detection.ratio.min-veins": 8, "detection.ratio.max-stone-per-vein": 15,
    "detection.direct.enabled": True, "detection.direct.threshold": 4,
    "detection.direct.lookback-blocks": 50, "detection.direct.min-start-distance": 8,
    "detection.alert-cooldown-seconds": 120, "detection.rare-ores": "DIAMOND_ORE: 1.0\n",
    "detection.common-blocks": "STONE\nDEEPSLATE", "actions.enabled": False,
    "actions.min-alerts": 3, "actions.types": "DIRECT\nDENSE", "actions.commands": "lc warn {player} {type}",
}
API_FIXTURES = {
    "/api/auth/me": {"username": "fixture", "role": "admin", "roleName": "管理员", "permissions": ["*"]},
    "/api/auth/info": {"brand": "LunarCore 琉月", "gameLogin": False, "version": "0.0.9-pre.1"},
    "/api/panels": PANELS,
    "/api/ext/chat/ai/settings": AI,
    "/api/ext/qqauth/stats": [{"label": "模块状态", "value": "就绪"}, {"label": "账号数", "value": 2}],
    "/api/ext/qqauth/accounts": page_data([
        {"uuid": "b3c6a006-4d0c-4b4d-b3e2-dcfa09c8e7e6", "name": "Steve", "locked": False, "authenticated": True, "createdAt": NOW},
        {"uuid": "b3c6a006-4d0c-4b4d-b3e2-dcfa09c8e7e7", "name": "Alex", "locked": True, "authenticated": False, "createdAt": NOW},
    ]),
    "/api/ext/qqauth/config": {"values": QQ_VALUES, "messages": {"login.bind-required": "<yellow>请验证 QQ，验证码 <white>{code}</white>。"},
                              "revision": "fixture-qq-revision", "configuredSecrets": {"integration.astrbot.token": True}, "requiresReload": False},
    "/api/ext/terrain/status": TERRAIN,
    "/api/ext/multiverse/status": [{"label": "Multiverse桥接", "value": "Multiverse公开API可用", "icon": "globe"}],
    "/api/ext/multiverse/worlds": page_data([
        {"name": "world", "key": "minecraft:overworld", "alias": "梦幻生存", "loaded": True, "players": 2,
         "pvp": False, "allowWeather": True, "autoLoad": True, "terrainManaged": False},
        {"name": "archive", "key": "minecraft:archive", "alias": "归档世界", "loaded": False, "players": 0,
         "pvp": False, "allowWeather": False, "autoLoad": False, "terrainManaged": False},
    ]),
    "/api/ext/anticheat/stats": [{"label": "连接状态", "value": "已连接"}, {"label": "近期记录", "value": 1}],
    "/api/ext/ban/punishments/active": page_data([PUNISHMENT]),
    "/api/ext/ban/punishments/all": page_data([PUNISHMENT]),
    "/api/ext/ban/config": {
        "server-name": "琉月生存", "appeal-url": "", "date-format": "yyyy-MM-dd HH:mm",
        "default-reason": "违反服务器规则", "broadcast.enabled": True, "broadcast.api": True,
        "exempt-enabled": True, "warn.expire": "7d", "warn.actions": "3:lc kick {player} 违反服务器规则",
        "warn.reset-after-action": True, "muted-commands": "msg\nme",
        "duration-limits.enabled": False, "duration-limits.default": "30d", "reasons": "cheat: 违规行为\n",
    },
    "/api/ext/anticheat/violations": page_data([
        {"time": NOW, "player": "Steve", "check": "Reach", "vl": 2, "detail": "3.2", "ping": 40, "world": "world"}
    ]),
    "/api/ext/anticheat/settings": {"bridge.capture-enabled": True, "bridge.recent-capacity": 1000},
    "/api/ext/anticheat/commands": {"artifact": "grimac-bukkit-2.3.75-b08e9b1.jar", "commands": [
        {"id": "help", "label": "命令帮助", "command": "grim help", "permission": "anticheat.view", "grimPermission": "grim.help", "supported": True, "reason": "", "args": []},
        {"id": "profile", "label": "玩家检测档案", "command": "grim profile", "permission": "anticheat.view",
         "grimPermission": "grim.profile", "supported": True, "reason": "", "args": [
             {"name": "target", "label": "target", "type": "player", "required": True, "options": [], "min": 1, "max": 2147483647}]},
    ]},
    "/api/ext/anticheat/grim/config": {
        "files": [{"id": item["id"], "name": item["name"], "label": item["label"], "available": True}
                  for item in [GRIM_CONFIG, GRIM_PUNISHMENTS]], "selected": GRIM_CONFIG},
    "/api/ext/anticheat/grim/config/config": GRIM_CONFIG,
    "/api/ext/anticheat/grim/config/punishments": GRIM_PUNISHMENTS,
    "/api/ext/antixray/stats": [
        {"label": "今日告警", "value": 1}, {"label": "告警总数", "value": 1},
        {"label": "Paper anti-xray", "value": "可用"}, {"label": "假矿方案", "value": "未开启"},
        {"label": "伪装中的矿石", "value": 0}, {"label": "在线玩家", "value": 2},
    ],
    "/api/ext/antixray/alerts": page_data([{"id": 1, "created": NOW, "name": "Steve", "typeLabel": "直线命中",
                                          "detail": "30 分钟内多次直达未暴露矿脉", "location": "world (12, -36, 20)"}]),
    "/api/ext/antixray/suspects": page_data([{"uuid": "b3c6a006-4d0c-4b4d-b3e2-dcfa09c8e7e6", "name": "Steve",
                                            "score": 3, "alerts": 1, "perThousand": "8.0", "rare": 12,
                                            "stone": 1500, "lastAlert": NOW, "online": True}]),
    "/api/ext/antixray/paper": page_data([{"target": "defaults", "enabled": True, "engine": 2, "maxHeight": 64,
                                         "updateRadius": 2, "hidden": 14, "inherited": "", "file": "config/paper-world-defaults.yml"}]),
    "/api/ext/antixray/config": XRAY_CONFIG,
}


def check_layout(page, workspace):
    result = page.evaluate("""selector => {
      const main = document.querySelector('#main-content'), work = document.querySelector(selector)
      return [document.documentElement, main, work].filter(Boolean).map(e => ({
        tag: e.id || e.className || e.tagName, width: e.clientWidth, scroll: e.scrollWidth
      })).filter(e => e.scroll > e.width + 2)
    }""", workspace)
    assert not result, result


def visible(page, selector):
    expect(page.locator(f"{selector}:visible").first).to_be_visible()


def screenshot(page, name):
    page.locator("#main-content").evaluate("e => e.scrollTop = 0")
    page.screenshot(path=str(OUTPUT / f"{name}.png"), animations="disabled")


def run_checks(page, name, size, state):
    workspace = {
        "ai": ".ai-workspace", "qqauth": ".qq-workspace", "terrain": ".terrain-workspace",
        "multiverse": ".multiverse-workspace", "moderation": ".moderation-workspace", "antixray": ".antixray-workspace",
    }[name]
    visible(page, workspace)
    visited = []

    def capture(label):
        check_layout(page, workspace)
        assert not state["errors"], state["errors"]
        assert not state["unmocked"], state["unmocked"]
        assert not state["writes"], state["writes"]
        screenshot(page, f"{name}-{size}-{label}")
        visited.append(label)

    def tab(label, control, snapshot):
        page.get_by_role("tab", name=label, exact=True).click()
        visible(page, control)
        capture(snapshot)

    if name == "ai":
        expect(page.locator("#ai-name")).to_have_value("月灵")
        visible(page, "#ai-proactive-probability")
        visible(page, 'textarea[aria-label="人格设定"]')
        capture("identity")
        tab("模型连接", "#ai-key", "connection")
        tab("附加角色", "#bot-name-nebula", "bots")
        tab("语义记忆", "#ai-embedding-model", "memory")
        tab("回复与预览", "#ai-error-message", "messages")
        expect(page.locator(".chat-example")).to_be_visible()
    elif name == "qqauth":
        expect(page.locator(".account-row")).to_have_count(2)
        capture("accounts")
        tab("认证设置", ".qq-config", "config")
        for label, selector, slug in [
            ("QQ 桥接", '[id="qq-integration.astrbot.port"]', "bridge"),
            ("验证码与绑定", '[id="qq-binding.code-length"]', "binding"),
            ("密码与会话", '[id="qq-auth.min-password-length"]', "password"),
            ("绑定配额", 'input[aria-label="QQ 号码"]', "quotas"),
            ("提示消息", 'textarea[aria-label="提示消息内容"]', "messages"),
        ]:
            page.locator(".config-tabs").get_by_role("button", name=label, exact=True).click()
            visible(page, selector)
            capture(slug)
    elif name == "terrain":
        page.locator("#terrain-tab-worlds").click()
        visible(page, ".world-table")
        page.get_by_role("button", name="mountain", exact=True).click()
        visible(page, "#terrain-spawn-y")
        visible(page, "#terrain-player")
        capture("worlds")
        page.locator("#terrain-tab-create").click()
        visible(page, "#terrain-name")
        visible(page, "#terrain-height")
        capture("create")
        page.locator(".custom-height summary").click()
        visible(page, "#terrain-min-y")
        visible(page, "#terrain-max-y")
        page.locator(".advanced-group summary").last.click()
        visible(page, '[id="terrain-advanced-general.snow_start_offset"]')
        capture("advanced")
    elif name == "multiverse":
        expect(page.locator("#world-alias")).to_have_value("梦幻生存")
        visible(page, '.el-switch:has([aria-label="允许 PVP"])')
        capture("config")
        tab("世界操作", "#teleport-player", "operations")
        page.locator(".world-option").filter(has_text="归档世界").click()
        expect(page.get_by_role("button", name="加载世界", exact=True)).to_be_visible()
        capture("unloaded")
    elif name == "moderation":
        visible(page, ".el-table__body")
        capture("punishments")
        tab("GrimAC 违规", ".el-table__body", "violations")
        tab("管理工具", ".tool-list", "tools")
        page.get_by_role("button", name="玩家检测档案", exact=False).click()
        visible(page, ".tool-form input")
        capture("profile-tool")
        tab("GrimAC 配置", ".grim-config-workspace", "grim-config")
        visible(page, '[data-field-key="alerts.print-to-console"]')
        page.locator('[data-config-file="punishments"]').click()
        visible(page, '[data-field-key="Punishments.Reach.commands"]')
        capture("grim-punishments")
        tab("设置", ".form-surface", "settings")
    elif name == "antixray":
        visible(page, ".el-table__body")
        capture("alerts")
        page.locator(".record-switch").get_by_role("button", name="可疑玩家排行", exact=True).click()
        visible(page, ".el-table__body")
        capture("suspects")
        tab("Paper 防矿透", ".restart-notice", "paper")
        expect(page.locator("#antixray-pane-paper .el-table__body")).to_be_visible()
        tab("行为监测配置", ".config-navigation", "config")
        for label, key, slug in [
            ("行为检测", "detection.enabled", "detection"),
            ("假矿补充", "fake-ore.enabled", "fakeore"),
            ("告警联动", "actions.enabled", "actions"),
        ]:
            page.locator(".config-navigation").get_by_role("button", name=label, exact=True).click()
            visible(page, f'[data-field-key="{key}"]')
            capture(slug)
    return visited


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    selected_workspaces = os.environ.get("LC_UI_WORKSPACES", "").split(",")
    selected_workspaces = [value.strip() for value in selected_workspaces if value.strip()]
    report_path = OUTPUT / "report.json"
    report = {"fixtureOnly": True, "mutations": False, "cases": [], "failures": []}
    if selected_workspaces and report_path.exists():
        previous = json.loads(report_path.read_text(encoding="utf-8"))
        report["cases"] = [case for case in previous["cases"] if case["workspace"] not in selected_workspaces]
        report["failures"] = [case for case in previous["failures"] if case["workspace"] not in selected_workspaces]
    paths = {
        "ai": "/panel/chat/ai", "qqauth": "/panel/qqauth/accounts", "terrain": "/panel/terrain/tectonic",
        "multiverse": "/panel/multiverse/worlds", "moderation": "/panel/ban/ban", "antixray": "/panel/antixray/antixray",
    }
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=EDGE, headless=True)
        try:
            for name, path in paths.items():
                if selected_workspaces and name not in selected_workspaces:
                    continue
                for size, width, height in [("desktop", 1440, 1040), ("mobile", 390, 844)]:
                    state = {"errors": [], "unmocked": [], "writes": [], "reads": []}
                    context = browser.new_context(viewport={"width": width, "height": height})
                    context.add_init_script(
                        "localStorage.setItem('lc-token','fixture-token');"
                        "localStorage.setItem('lc-locale','zh_CN');"
                        "localStorage.setItem('lc-theme','light');"
                    )

                    def respond(route):
                        request = route.request
                        parsed = urlparse(request.url)
                        if f"{parsed.scheme}://{parsed.netloc}" != BASE:
                            if parsed.netloc == "mc-heads.net" and parsed.path.startswith("/avatar/"):
                                route.fulfill(content_type="image/png", body=base64.b64decode(
                                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nKAAAAAASUVORK5CYII="))
                                return
                            state["unmocked"].append(f"External request: {parsed.netloc}{parsed.path}")
                            route.abort()
                            return
                        if not parsed.path.startswith("/api/"):
                            route.continue_()
                            return
                        if request.method != "GET":
                            state["writes"].append(f"{request.method} {parsed.path}")
                            route.fulfill(status=405, json={"error": "Read-only UI fixture"})
                            return
                        if parsed.path not in API_FIXTURES:
                            state["unmocked"].append(f"GET {parsed.path}")
                            route.fulfill(status=404, json={"error": "Unexpected fixture endpoint"})
                            return
                        state["reads"].append(parsed.path)
                        route.fulfill(json=deepcopy(API_FIXTURES[parsed.path]))

                    context.route("**/*", respond)
                    context.route_web_socket("**/api/ws**", lambda connection: connection.on_message(lambda _: None))
                    page = context.new_page()
                    page.on("pageerror", lambda error: state["errors"].append(str(error)))
                    try:
                        page.goto(BASE + path, wait_until="networkidle")
                        visited = run_checks(page, name, size, state)
                        report["cases"].append({"workspace": name, "viewport": size, "sections": visited,
                                                "apiReads": sorted(set(state["reads"]))})
                        print(f"PASS {name} {size}: {', '.join(visited)}", flush=True)
                    except Exception as error:
                        page.screenshot(path=str(OUTPUT / f"{name}-{size}-failure.png"), animations="disabled")
                        report["failures"].append({"workspace": name, "viewport": size, "error": str(error),
                                                   "pageErrors": state["errors"], "unmocked": state["unmocked"],
                                                   "writes": state["writes"]})
                        print(f"FAIL {name} {size}: {error}", flush=True)
                    finally:
                        context.close()
        finally:
            browser.close()
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": len(report["cases"]), "failed": len(report["failures"]),
                      "output": str(OUTPUT)}, ensure_ascii=False), flush=True)
    raise SystemExit(1 if report["failures"] else 0)


if __name__ == "__main__":
    main()
