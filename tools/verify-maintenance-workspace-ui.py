"""Exercise maintenance controls in the real WebUI using isolated API fixtures."""
import json
import os
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


BASE = os.environ.get("LC_UI_BASE", "http://127.0.0.1:5216")
OUTPUT = Path(__file__).resolve().parents[1] / "artifacts/maintenance-review-20261115"
EDGE = "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
PANELS = [{"namespace": "motd", "id": "motd", "title": "服务器展示与维护",
           "icon": "server", "group": "系统", "permission": "motd.view", "sections": []}]
NORMAL = {"enabled": True, "line1": "<green>梦幻生存", "line2": "<white>{online}/{max} · {version}"}
MAINTENANCE = {
    "enabled": False, "allowJoin": False, "kickOnline": False,
    "scheduleEnabled": False, "scheduleStart": "", "scheduleEnd": "",
    "whitelist": "trusted_user", "joinMessage": "<yellow>服务器维护中，请稍后重试。",
    "kickMessage": "<gold>维护已开始，请稍后重新连接。",
    "line1": "<gold>服务器维护中", "line2": "<white>请稍后重试。",
}


def layout(page):
    expect(page.locator(".maintenance-workspace")).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2")
    for selector in [".maintenance-workspace", ".maintenance-savebar", ".control-layout"]:
        assert all(page.locator(selector).evaluate_all(
            "nodes => nodes.filter(n => n.getClientRects().length).map(n => n.scrollWidth <= n.clientWidth + 2)"
        )), selector


def saved(page):
    expect(page.locator(".maintenance-workspace")).to_have_attribute("aria-busy", "false")
    expect(page.locator(".feedback.success")).to_be_visible()


def mobile(page):
    page.set_viewport_size({"width": 390, "height": 844})
    expect(page.locator(".app-sidebar")).to_have_attribute("aria-hidden", "true")
    page.wait_for_function("document.querySelector('.app-sidebar').getBoundingClientRect().right <= 1")


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(executable_path=EDGE, headless=True)
    contexts, errors, unknown = [], [], []
    OUTPUT.mkdir(parents=True, exist_ok=True)

    def open_page(language="zh_CN", manage=True, fail_read=False):
        state = {"normal": deepcopy(NORMAL), "maintenance": deepcopy(MAINTENANCE), "writes": [],
                 "fail_read": fail_read, "force_active": None, "hold_write": False,
                 "pending_write": None, "status_reads": 0}
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        contexts.append(context)
        context.add_init_script(
            "localStorage.setItem('lc-token','fixture-token');"
            f"localStorage.setItem('lc-locale',{json.dumps(language)});"
            "localStorage.setItem('lc-theme','light');"
        )

        def status():
            settings = state["maintenance"]
            now = datetime.now(timezone.utc)
            phase = "disabled"
            active = settings["enabled"]
            transition = ""
            if settings["scheduleEnabled"]:
                start = datetime.fromisoformat(settings["scheduleStart"].replace("Z", "+00:00"))
                end = datetime.fromisoformat(settings["scheduleEnd"].replace("Z", "+00:00")) if settings["scheduleEnd"] else None
                phase = "pending" if now < start else "completed" if end and now >= end else "active"
                active = phase == "active"
                transition = settings["scheduleStart"] if phase == "pending" else settings["scheduleEnd"] if phase == "active" else ""
            if state["force_active"] is not None:
                active = state["force_active"]
                phase = "active" if active else "completed"
            return [{
                "enabled": active, "manualEnabled": settings["enabled"],
                "controlMode": "scheduled" if settings["scheduleEnabled"] else "manual",
                "scheduleEnabled": settings["scheduleEnabled"], "scheduleActive": active and settings["scheduleEnabled"],
                "schedulePhase": phase, "scheduleStart": settings["scheduleStart"], "scheduleEnd": settings["scheduleEnd"],
                "nextTransitionAt": transition, "serverTime": now.isoformat(),
                "allowJoin": settings["allowJoin"], "kickOnline": settings["kickOnline"],
                "online": 3, "max": 100, "version": "26.3", "eligible": 2 if active else 0,
            }]

        def respond(route):
            request = route.request
            parsed = urlparse(request.url)
            if f"{parsed.scheme}://{parsed.netloc}" != BASE:
                route.abort()
                return
            path = parsed.path
            if not path.startswith("/api/"):
                if request.is_navigation_request():
                    response = route.fetch()
                    # The plugin injects this base tag when serving the built application.
                    html = response.text().replace("<head>", '<head><base href="/">', 1)
                    route.fulfill(response=response, body=html)
                    return
                route.continue_()
                return
            if path == "/api/auth/me":
                body = {"username": "fixture", "role": "admin", "permissions": ["motd.view"] + (["motd.manage"] if manage else [])}
            elif path == "/api/auth/info":
                body = {"brand": "LunarCore 琉月", "version": "0.0.9-pre.1", "ready": True}
            elif path == "/api/panels":
                body = PANELS
            elif path == "/api/ext/motd/maintenance/status":
                state["status_reads"] += 1
                body = status()
            elif path in ("/api/ext/motd/settings", "/api/ext/motd/maintenance"):
                if state["fail_read"]:
                    route.fulfill(status=503, json={"error": "Fixture read failure"})
                    return
                key = "normal" if path.endswith("/settings") else "maintenance"
                if request.method == "POST":
                    payload = request.post_data_json
                    state["writes"].append((key, deepcopy(payload)))
                    if state["hold_write"]:
                        state["pending_write"] = (route, key, deepcopy(payload))
                        request.frame.page.evaluate("window.__maintenanceWritePending = true")
                        return
                    state[key] = deepcopy(payload)
                    body = {"ok": True, "message": "已保存"}
                else:
                    body = deepcopy(state[key])
            elif path == "/api/ext/motd/maintenance/kick":
                state["writes"].append(("kick", request.post_data_json))
                body = {"ok": True, "message": "已发起维护踢出请求"}
            else:
                unknown.append((request.method, path))
                route.fulfill(status=404, json={"error": "Unexpected fixture route"})
                return
            route.fulfill(json=body)

        context.route("**/*", respond)
        context.route_web_socket("**/api/ws**", lambda connection: connection.on_message(lambda _: None))
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(BASE + "/panel/motd/motd", wait_until="networkidle")
        return page, state

    try:
        page, state = open_page(fail_read=True)
        expect(page.get_by_role("alert").filter(has_text="Fixture read failure")).to_be_visible()
        state["fail_read"] = False
        page.get_by_role("button", name="重新加载", exact=True).click()
        expect(page.get_by_role("radio", name="手动控制", exact=True)).to_be_checked()
        expect(page.locator(".live-status")).to_have_text("正常开放")
        layout(page)
        page.screenshot(path=str(OUTPUT / "maintenance-desktop-control-zh.png"), full_page=True)

        page.get_by_role("tab", name="白名单", exact=True).click()
        page.locator("#maintenance-whitelist").fill("draft_user")
        page.get_by_role("tab", name="维护控制", exact=True).click()
        page.get_by_role("radio", name="定时维护", exact=True).check()
        start = datetime.now().replace(second=0, microsecond=0) + timedelta(hours=1)
        end = start + timedelta(hours=2)
        page.locator("#maintenance-schedule-start").fill(start.strftime("%Y-%m-%dT%H:%M"))
        page.locator("#maintenance-schedule-end").fill((start - timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M"))
        page.get_by_role("button", name="保存当前分区", exact=True).click()
        expect(page.get_by_role("alert").filter(has_text="维护结束时间必须晚于开始时间")).to_be_visible()
        assert not state["writes"]
        page.locator("#maintenance-schedule-end").fill(end.strftime("%Y-%m-%dT%H:%M"))
        page.get_by_role("button", name="保存当前分区", exact=True).click()
        saved(page)
        expect(page.get_by_role("button", name="保存当前分区", exact=True)).to_be_disabled()
        assert state["maintenance"]["scheduleEnabled"] is True
        assert state["maintenance"]["whitelist"] == "trusted_user", "Saving controls must not apply another tab's draft"
        assert datetime.fromisoformat(state["maintenance"]["scheduleStart"].replace("Z", "+00:00")).timestamp() == start.timestamp()
        expect(page.locator(".live-detail")).to_contain_text("等待开始")

        mobile(page)
        layout(page)
        page.screenshot(path=str(OUTPUT / "maintenance-mobile-schedule-zh.png"), full_page=True)
        page.locator("#maintenance-tab-whitelist").click()
        expect(page.locator("#maintenance-whitelist")).to_have_value("draft_user")
        state["force_active"] = True
        expect(page.locator(".live-status")).to_have_text("维护中", timeout=8000)
        expect(page.locator("#maintenance-whitelist")).to_have_value("draft_user")
        assert state["status_reads"] >= 2, "Status must refresh while an unrelated draft is present"
        page.get_by_role("button", name="保存当前分区", exact=True).click()
        saved(page)
        expect(page.get_by_role("button", name="保存当前分区", exact=True)).to_be_disabled()
        assert state["maintenance"]["whitelist"] == "draft_user"

        page.get_by_role("tab", name="提示消息", exact=True).click()
        page.locator("#maintenance-join-message").fill("<red>正在更新，请稍后重试。")
        state["hold_write"] = True
        page.get_by_role("button", name="保存当前分区", exact=True).click()
        expect(page.locator("#maintenance-kick-message")).to_be_disabled()
        expect(page.get_by_role("button", name="保存当前分区", exact=True)).to_be_disabled()
        page.wait_for_function("window.__maintenanceWritePending === true")
        route, key, payload = state["pending_write"]
        assert payload["whitelist"] == "draft_user"
        state[key] = deepcopy(payload)
        route.fulfill(json={"ok": True, "message": "已保存"})
        state["pending_write"] = None
        state["hold_write"] = False
        expect(page.locator("#maintenance-kick-message")).to_be_enabled()
        layout(page)
        page.screenshot(path=str(OUTPUT / "maintenance-mobile-messages-zh.png"), full_page=True)

        page.get_by_role("tab", name="服务器简介", exact=True).click()
        page.locator("#normal-line1").fill("<green>新的服务器简介")
        page.get_by_role("button", name="保存当前分区", exact=True).click()
        saved(page)
        expect(page.get_by_role("button", name="保存当前分区", exact=True)).to_be_disabled()
        assert state["normal"]["line1"] == "<green>新的服务器简介"
        assert state["maintenance"]["line1"] == MAINTENANCE["line1"]
        page.screenshot(path=str(OUTPUT / "maintenance-mobile-motd-zh.png"), full_page=True)
        page.get_by_role("tab", name="维护控制", exact=True).click()
        page.get_by_role("button", name="立即踢出", exact=True).click()
        expect(page.get_by_role("dialog")).to_contain_text("立即向非白名单在线玩家")
        page.get_by_role("button", name="取消", exact=True).click()
        assert not any(key == "kick" for key, _ in state["writes"])
        page.get_by_role("button", name="立即踢出", exact=True).click()
        page.get_by_role("button", name="确认执行", exact=True).click()
        expect(page.get_by_role("dialog")).not_to_be_visible()
        saved(page)
        assert sum(key == "kick" for key, _ in state["writes"]) == 1

        readonly, _ = open_page(manage=False)
        expect(readonly.get_by_role("switch", name="启用维护模式", exact=True)).to_be_disabled()
        expect(readonly.get_by_role("button", name="立即踢出", exact=True)).to_be_disabled()

        english, _ = open_page(language="en_US")
        expect(english.get_by_role("heading", name="Server Display and Maintenance", exact=True)).to_be_visible()
        expect(english.get_by_role("tab", name="Messages", exact=True)).to_be_visible()
        layout(english)
        english.screenshot(path=str(OUTPUT / "maintenance-desktop-control-en.png"), full_page=True)
        mobile(english)
        english.get_by_role("tab", name="Messages", exact=True).click()
        layout(english)
        english.screenshot(path=str(OUTPUT / "maintenance-mobile-messages-en.png"), full_page=True)
        assert not unknown, unknown
        assert not errors, errors
        print(json.dumps({"result": "passed", "checks": [
            "read-error recovery", "independent section saves", "local date/time conversion", "invalid schedule prevention",
            "live schedule status with draft preservation", "save locking", "normal/maintenance MOTD isolation",
            "manual kick confirmation and cancellation", "view-only permissions", "Chinese/English desktop/mobile layout"
        ], "screenshots": 6, "pageErrors": errors, "unmocked": unknown}, ensure_ascii=False))
    finally:
        for context in contexts:
            context.close()
        browser.close()
