"""Verify GrimAC configuration UI using intercepted APIs; never touches a server."""
import json
import os
import sys
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


BASE = os.environ.get("LBR_UI_BASE", "http://127.0.0.1:5197")
OUTPUT = Path(__file__).resolve().parents[1] / "artifacts" / "grim-config-ui"
PRIVATE_VALUE = "fixture-private-webhook-must-not-reach-browser"
PREFIX = "/api/ext/anticheat"
PANELS = [{
    "namespace": "anticheat", "id": "anticheat", "title": "反作弊",
    "group": "安全", "icon": "shield-alert", "permission": "anticheat.view",
    "sections": [],
}]


def field(key, label, group, kind, **metadata):
    return {"key": key, "label": label, "group": group, "type": kind, **metadata}


def snapshot(identifier, name, label, fields, values):
    return {
        "id": identifier, "name": name, "label": label,
        "fingerprint": "sha256:" + "a" * 64, "fields": fields, "values": values,
        "reloadRequired": True, "restartRequired": False,
    }


def fixture():
    return {
        "documents": {
            "config": snapshot("config", "config.yml", "主配置", [
                field("alerts.print-to-console", "控制台违规告警", "告警与通知", "bool"),
                field("max-transaction-time", "事务超时（秒）", "网络与兼容", "number",
                      min=1, max=180, step=1),
                field("client-brand.ignored-clients", "忽略的客户端品牌", "客户端识别",
                      "string-list", help="按顺序匹配客户端品牌的正则表达式。"),
                field("Simulation.threshold", "移动偏差阈值", "移动模拟", "number", step=0.0001),
                field("config-flavor", "配置模式", "配置版本", "text", readOnly=True),
            ], {
                "alerts.print-to-console": True, "max-transaction-time": 60,
                "client-brand.ignored-clients": ["^vanilla$", ""],
                "Simulation.threshold": 0.001, "config-flavor": "V2",
            }),
            "punishments": snapshot("punishments", "punishments.yml", "处罚规则", [
                field("Punishments.Simulation.remove-violations-after", "违规过期时间（秒）",
                      "移动模拟", "number", min=0, step=1),
                field("Punishments.Simulation.checks", "关联检测", "移动模拟", "string-list"),
                field("Punishments.Simulation.commands", "处罚命令", "移动模拟",
                      "punishment-commands"),
            ], {
                "Punishments.Simulation.remove-violations-after": 300,
                "Punishments.Simulation.checks": ["Simulation", "!BadPacketsN"],
                "Punishments.Simulation.commands": ["100:40 [alert]", "1:1 [log]"],
            }),
            "messages": snapshot("messages", "messages.yml", "消息文本", [
                field("prefix", "消息前缀", "通用消息", "text"),
                field("help", "命令帮助", "通用消息", "string-list"),
            ], {"prefix": "&bGrim", "help": ["", "%prefix% 命令帮助"]}),
            "discord": snapshot("discord", "discord.yml", "Discord 告警", [
                field("enabled", "启用 Discord 告警", "告警设置", "bool"),
                field("webhook", "Webhook 地址", "告警设置", "text", secret=True, configured=True),
                field("violation-content", "告警内容", "告警设置", "string-list"),
            ], {"enabled": False, "webhook": "", "violation-content": ["玩家：%player%"]}),
            "database": snapshot("database", "database.yml", "历史存储", [
                field("database.enabled", "启用违规历史", "历史存储", "bool"),
                field("database.write-path.wait-strategy", "队列等待策略", "写入队列", "select",
                      options=[{"value": "BLOCKING", "label": "阻塞等待"},
                               {"value": "SLEEPING", "label": "休眠等待"}]),
            ], {"database.enabled": True, "database.write-path.wait-strategy": "BLOCKING"}),
        },
        "writes": [], "reads": [], "reject_save": False, "reloads": 0, "health_reads": 0,
        "unmocked": [],
    }


def no_overflow(page):
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2")
    assert page.locator(".grim-config-workspace").evaluate(
        "element => element.scrollWidth <= element.clientWidth + 2")


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        executable_path="C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
        headless=True,
    )
    contexts = []
    errors = []
    checks = []

    def open_page(state, permissions=None):
        context = browser.new_context(viewport={"width": 1440, "height": 1040})
        contexts.append(context)
        context.add_init_script(
            "localStorage.setItem('lbr-token','fixture-token');"
            "localStorage.setItem('lbr-locale','zh_CN');"
            "localStorage.setItem('lbr-theme','light');"
        )

        def respond(route):
            request = route.request
            path = urlparse(request.url).path
            status = 200
            if path == "/api/auth/me":
                body = {"username": "fixture", "role": "moderator",
                        "permissions": permissions if permissions is not None
                        else ["anticheat.view", "anticheat.manage"]}
            elif path == "/api/auth/info":
                body = {"brand": "LunarCore", "gameLogin": False}
            elif path == "/api/panels":
                body = PANELS
            elif path == PREFIX + "/stats":
                body = [{"label": "连接状态", "value": "已连接", "icon": "shield-alert"}]
            elif path == PREFIX + "/health":
                state["health_reads"] += 1
                body = {"connected": True,
                        "reloadStatus": "重载成功" if state["reloads"] else "尚未重载"}
            elif path == PREFIX + "/grim/reload":
                assert request.method == "POST"
                state["reloads"] += 1
                body = {"ok": True, "message": "已提交 GrimAC 配置重载"}
            elif path == PREFIX + "/grim/config":
                state["reads"].append("directory")
                body = {
                    "files": [{key: document[key] for key in ("id", "name", "label")}
                              | {"available": True}
                              for document in state["documents"].values()],
                    "selected": deepcopy(state["documents"]["config"]),
                }
            elif path.startswith(PREFIX + "/grim/config/"):
                identifier = path.rsplit("/", 1)[-1]
                document = state["documents"][identifier]
                if request.method == "POST":
                    data = request.post_data_json
                    state["writes"].append({"id": identifier, "body": data})
                    if state["reject_save"]:
                        body = {"error": "配置已被其他位置修改，请重新读取后核对。"}
                        status = 409
                    else:
                        document["values"].update(deepcopy(data["changes"]))
                        for metadata in document["fields"]:
                            if metadata.get("secret"):
                                value = document["values"].pop(metadata["key"], "")
                                if metadata["key"] in data["changes"]:
                                    metadata["configured"] = bool(value)
                                document["values"][metadata["key"]] = ""
                        document["fingerprint"] = "sha256:" + "b" * 64
                        body = {"ok": True, "message": "配置已保存，请重载 GrimAC",
                                "snapshot": deepcopy(document)}
                else:
                    state["reads"].append(identifier)
                    body = deepcopy(document)
            else:
                state["unmocked"].append(f"{request.method} {path}")
                body = {"error": "Unexpected fixture request"}
                status = 404
            route.fulfill(status=status, json=body)

        context.route("**/api/**", respond)
        context.route_web_socket("**/api/ws**", lambda connection: connection.on_message(lambda _: None))
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(BASE + "/panel/anticheat/anticheat", wait_until="networkidle")
        expect(page.get_by_role("tab", name="GrimAC 违规", exact=True)).to_be_visible()
        return page

    try:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        state = fixture()
        page = open_page(state)
        assert not state["reads"], "Configuration is loaded only after opening its tab."
        page.get_by_role("tab", name="GrimAC 配置", exact=True).click()
        timeout = page.locator('[data-field-key="max-transaction-time"] input[type=number]')
        expect(timeout).to_have_value("60")
        assert state["reads"] == ["directory"]
        no_overflow(page)
        page.screenshot(path=str(OUTPUT / "desktop-light.png"), full_page=True, animations="disabled")
        checks.append("Configuration is loaded lazily and shows Chinese labels alongside original keys.")

        timeout.fill("75")
        timeout.press("Tab")
        page.locator('[data-config-file="discord"]').click()
        discard = page.get_by_role("dialog", name="未保存的修改", exact=True)
        expect(discard).to_be_visible()
        discard.get_by_role("button", name="继续编辑", exact=True).click()
        expect(timeout).to_have_value("75")
        page.get_by_role("button", name="保存配置", exact=True).click()
        expect(page.get_by_role("button", name="保存配置", exact=True)).to_be_disabled()
        assert state["writes"][-1]["body"]["changes"] == {"max-transaction-time": 75}
        assert state["reloads"] == 0, "Saving never reloads GrimAC implicitly."
        checks.append("Cancelling a file switch keeps the draft; save sends only the changed setting.")

        page.locator('[data-config-file="punishments"]').click()
        rules = page.locator('[data-field-key="Punishments.Simulation.commands"]')
        expect(rules.get_by_role("spinbutton", name="首次违规等级").first).to_have_value("100")
        rules.get_by_role("spinbutton", name="重复间隔").first.fill("0")
        rules.get_by_role("spinbutton", name="重复间隔").first.press("Tab")
        page.get_by_role("button", name="保存配置", exact=True).click()
        expect(page.get_by_role("button", name="保存配置", exact=True)).to_be_disabled()
        assert state["writes"][-1]["body"]["changes"] == {
            "Punishments.Simulation.commands": ["100:0 [alert]", "1:1 [log]"]}
        checks.append("Structured punishment rules preserve order and serialize interval zero correctly.")
        expect(page.locator(".el-message")).to_have_count(0, timeout=6000)
        rules.scroll_into_view_if_needed()
        page.screenshot(path=str(OUTPUT / "punishments-desktop.png"),
                        full_page=True, animations="disabled")
        page.set_viewport_size({"width": 390, "height": 844})
        rules.scroll_into_view_if_needed()
        no_overflow(page)
        page.screenshot(path=str(OUTPUT / "punishments-mobile.png"),
                        full_page=True, animations="disabled")
        page.set_viewport_size({"width": 1440, "height": 1040})

        page.locator('[data-config-file="messages"]').click()
        expect(page.locator('[data-field-key="help"] input').first).to_have_value("")
        page.locator('[data-config-file="discord"]').click()
        webhook = page.locator('[data-field-key="webhook"]')
        expect(webhook.get_by_text("已设置，原值不显示", exact=True)).to_be_visible()
        assert PRIVATE_VALUE not in page.content()
        assert webhook.locator("input").count() == 0
        page.locator('[data-field-key="enabled"] .el-switch').click()
        page.get_by_role("button", name="保存配置", exact=True).click()
        expect(page.get_by_role("button", name="保存配置", exact=True)).to_be_disabled()
        assert state["writes"][-1]["body"]["changes"] == {"enabled": True}
        webhook.get_by_role("button", name="修改", exact=True).click()
        expect(webhook.locator("input")).to_have_value("")
        page.get_by_role("button", name="保存配置", exact=True).click()
        expect(webhook.get_by_text("尚未设置", exact=True)).to_be_visible()
        assert state["writes"][-1]["body"]["changes"] == {"webhook": ""}
        checks.append("Saved secrets stay hidden and unchanged unless explicitly replaced or cleared.")

        page.locator('[data-config-file="config"]').click()
        timeout = page.locator('[data-field-key="max-transaction-time"] input[type=number]')
        timeout.fill("80")
        timeout.press("Tab")
        state["reject_save"] = True
        page.get_by_role("button", name="保存配置", exact=True).click()
        expect(page.get_by_text("配置已被其他位置修改，请重新读取后核对。", exact=False)).to_be_visible()
        expect(timeout).to_have_value("80")
        expect(page.get_by_role("button", name="保存配置", exact=True)).to_be_disabled()
        state["reject_save"] = False
        page.get_by_role("button", name="重新读取", exact=True).click()
        page.get_by_role("dialog").get_by_role("button", name="放弃修改", exact=True).click()
        expect(timeout).to_have_value("75")
        checks.append("A conflict keeps the draft and blocks retries until a deliberate reload.")

        page.get_by_role("button", name="重载 GrimAC", exact=True).click()
        page.get_by_role("dialog", name="重载 GrimAC", exact=True).get_by_role(
            "button", name="重载 GrimAC", exact=True).click()
        expect(page.get_by_text("重载成功", exact=True)).to_be_visible()
        assert state["reloads"] == 1 and state["health_reads"] >= 1
        checks.append("Applying changes requires an explicit reload and waits for its reported result.")

        no_overflow(page)
        page.get_by_role("button", name="切换到暗色", exact=True).click()
        page.screenshot(path=str(OUTPUT / "desktop-dark.png"), full_page=True, animations="disabled")
        page.set_viewport_size({"width": 390, "height": 844})
        no_overflow(page)
        page.screenshot(path=str(OUTPUT / "mobile-dark.png"), full_page=True, animations="disabled")
        page.get_by_role("button", name="切换到亮色", exact=True).click()
        page.screenshot(path=str(OUTPUT / "mobile-light.png"), full_page=True, animations="disabled")
        checks.append("Desktop and 390px mobile layouts fit in both light and dark themes.")

        limited = fixture()
        limited_page = open_page(limited, ["anticheat.view"])
        expect(limited_page.get_by_role("tab", name="GrimAC 配置", exact=True)).to_have_count(0)
        assert not limited["reads"] and not limited["writes"]
        checks.append("Accounts without anticheat.manage neither see nor request configuration.")
        if len(sys.argv) > 1:
            installed = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
            real_schema = fixture()
            real_schema["documents"] = {
                document["id"]: document for document in installed["snapshots"]}
            installed_page = open_page(real_schema)
            installed_page.get_by_role("tab", name="GrimAC 配置", exact=True).click()
            for identifier, document in real_schema["documents"].items():
                installed_page.locator(f'[data-config-file="{identifier}"]').click()
                expect(installed_page.locator(".file-heading h3")).to_have_text(document["label"])
                expect(installed_page.locator(".grim-field")).to_have_count(len(document["fields"]))
                expect(installed_page.locator(".field-error")).to_have_count(0)
                no_overflow(installed_page)
            assert not real_schema["writes"]
            installed_page.locator('[data-config-file="config"]').click()
            expect(installed_page.locator(".grim-field")).to_have_count(63)
            installed_page.get_by_role("textbox", name="搜索 GrimAC 配置", exact=True).fill("移动模拟")
            expect(installed_page.locator(".config-group h4")).to_have_text("移动模拟")
            no_overflow(installed_page)
            installed_page.screenshot(path=str(OUTPUT / "installed-schema-chinese.png"),
                                      full_page=True, animations="disabled")
            installed_page.get_by_role("button", name="语言", exact=True).click()
            installed_page.get_by_role("menuitem", name="English", exact=True).click()
            installed_page.get_by_role("textbox", name="Search GrimAC configuration", exact=True).fill("")
            expect(installed_page.locator(".file-heading h3")).to_have_text("Main configuration")
            expect(installed_page.locator(".grim-field").filter(
                has_text="向控制台输出告警")).to_have_count(0)
            installed_page.screenshot(path=str(OUTPUT / "installed-schema-english.png"),
                                      full_page=True, animations="disabled")
            checks.append("All 269 installed fields render without validation errors; Chinese search and English labels work.")
        assert not errors, errors
        assert not state["unmocked"] and not limited["unmocked"], (
            state["unmocked"], limited["unmocked"])
        (OUTPUT / "results.json").write_text(json.dumps({
            "mode": "mock-api", "checks": checks, "pageErrors": errors,
            "configReads": state["reads"], "configWrites": len(state["writes"]),
            "realServerWrites": 0,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print("PASS GrimAC visual configuration browser verification:")
        for check in checks:
            print("- " + check)
    finally:
        for context in contexts:
            context.close()
        browser.close()
