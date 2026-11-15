"""Verify the modules page against isolated API fixtures, never the live server."""
import argparse
from copy import deepcopy
from pathlib import Path
import re
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://127.0.0.1:5216")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = root / "artifacts/usability-review-20261115"
output.mkdir(parents=True, exist_ok=True)

state = {
    "language": "zh",
    "read_error": "Fixture initial read failure",
    "reject_write": False,
    "reject_panels": False,
    "hold_write": False,
    "hold_modules": False,
    "hold_panels": False,
    "pending_write": None,
    "pending_modules": None,
    "pending_panels": None,
    "writes": [],
    "panels_reads": 0,
    "enabled": {"chat": True, "teleport": True, "protection": False, "utility": False},
}
errors = []
unknown = []


def modules():
    chinese = state["language"] == "zh"
    names = [
        "AI 聊天" if chinese else "AI Chat",
        "跨世界传送与出生点管理（适用于多世界生存及主城）" if chinese
        else "Cross-world teleportation and server arrival routing for survival and lobby worlds",
        "领地保护" if chinese else "Land Protection",
        "便捷工具" if chinese else "Utility Tools",
    ]
    descriptions = [
        "在游戏内与玩家聊天，支持自定义名称、人格和模型。" if chinese
        else "Talk with players in game using a configurable name, personality and model.",
        "管理玩家传送、主城和每日首次上线位置。此描述故意使用较长的内容，检查窄屏换行、模块状态和按钮是否仍然清晰可读。"
        if chinese else
        "Manage player travel, the lobby and the first login of each day. This intentionally long description checks "
        "wrapping beside status badges and controls. LongDependencyIdentifierWithoutAnyWhitespaceMustStillWrapCorrectly.",
        "保护玩家建筑，管理成员与交互权限。" if chinese
        else "Protect player builds and manage member and interaction permissions.",
        "提供常用工具与玩家便捷功能。" if chinese
        else "Provide everyday tools and player conveniences.",
    ]
    records = []
    for index, module_id in enumerate(("chat", "teleport", "protection", "utility")):
        records.append({
            "id": module_id,
            "name": names[index],
            "description": descriptions[index],
            "version": "0.0.8-preview",
            "authors": ["LunarCore Team"],
            "depends": ["core"],
            "softDepends": ["Multiverse-Core", "PlaceholderAPI"] if module_id == "teleport" else [],
            "loaded": module_id != "utility",
            "enabled": state["enabled"][module_id],
        })
    return {"items": records}


def apply_write(route):
    module_id, action = urlparse(route.request.url).path.split("/")[-2:]
    if action in ("enable", "disable"):
        state["enabled"][module_id] = action == "enable"
    route.fulfill(json={"ok": True})


def respond(route):
    request = route.request
    path = urlparse(request.url).path
    if path == "/api/auth/info":
        body = {"brand": "LunarCore 琉月", "version": "0.0.8-preview", "ready": True}
    elif path == "/api/auth/me":
        body = {"username": "fixture", "role": "admin", "roleName": "Admin", "permissions": ["*"]}
    elif path == "/api/panels":
        state["panels_reads"] += 1
        if state["hold_panels"]:
            state["hold_panels"] = False
            state["pending_panels"] = route
            return
        if state["reject_panels"]:
            state["reject_panels"] = False
            route.fulfill(status=503, json={"error": "Fixture menu refresh failure"})
            return
        body = []
    elif path == "/api/modules":
        if state["hold_modules"]:
            state["hold_modules"] = False
            state["pending_modules"] = route
            return
        if state["read_error"]:
            route.fulfill(status=503, json={"error": state["read_error"]})
            return
        body = modules()
    elif path.startswith("/api/modules/") and request.method == "POST":
        state["writes"].append(path)
        if state["hold_write"]:
            state["hold_write"] = False
            state["pending_write"] = route
            return
        if state["reject_write"]:
            state["reject_write"] = False
            body = {"ok": False, "error": "Fixture rejected module action"}
        else:
            apply_write(route)
            return
    else:
        unknown.append((request.method, path))
        route.fulfill(status=404, json={"error": "Unexpected fixture route: " + path})
        return
    route.fulfill(json=deepcopy(body))


def assert_layout(page):
    failures = page.locator(".modules-page").evaluate(
        """root => {
          const failures = [];
          if (document.documentElement.scrollWidth > innerWidth + 2) failures.push('document overflow');
          const main = document.getElementById('main-content');
          if (main.scrollWidth > main.clientWidth + 2) failures.push('main overflow');
          for (const row of root.querySelectorAll('.mod')) {
            const bounds = row.getBoundingClientRect();
            for (const element of row.querySelectorAll('.mod-head,.mod-name,.mod-label,h2,.id,.details,.desc,.meta,.ops,.el-tag')) {
              if (!element.getClientRects().length) continue;
              const rect = element.getBoundingClientRect();
              if (rect.left < bounds.left - 2 || rect.right > bounds.right + 2)
                failures.push(element.className + ': outside row');
              if (element.scrollWidth > element.clientWidth + 2)
                failures.push(element.className + ': clipped content');
            }
          }
          return failures;
        }"""
    )
    assert not failures, failures


def assert_controls_locked(page):
    for button in page.locator(".modules-page .ops button").all():
        expect(button).to_be_disabled()
    expect(page.locator(".modules-page .toolbar button")).to_be_disabled()
    expect(page.get_by_role("tab", name="模块市场", exact=True)).to_have_class(re.compile(r"\bis-disabled\b"))


def wait_pending(page, key):
    for _ in range(100):
        if state[key] is not None:
            return state[key]
        page.wait_for_timeout(25)
    raise AssertionError("Fixture request was not received: " + key)


def screenshot(page, name):
    page.screenshot(path=str(output / ("modules-" + name + ".png")), animations="disabled")


with sync_playwright() as driver:
    browser = driver.chromium.launch(
        executable_path="C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
        headless=True,
    )
    context = browser.new_context(viewport={"width": 1440, "height": 1000}, reduced_motion="reduce")
    context.add_init_script(
        "localStorage.setItem('lc-token','fixture-session');"
        "if (!localStorage.getItem('lc-locale')) localStorage.setItem('lc-locale','zh_CN');"
    )
    context.route("**/api/**", respond)
    context.route_web_socket("**/api/ws**", lambda websocket: websocket.on_message(lambda message: None))
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(args.base + "/modules")

    expect(page.locator(".load-error")).to_contain_text("模块列表加载失败")
    expect(page.locator(".mod")).to_have_count(0)
    expect(page.locator(".el-empty")).to_have_count(0)
    screenshot(page, "initial-error-zh")
    state["read_error"] = ""
    page.get_by_role("button", name="重试", exact=True).click()
    expect(page.locator(".mod")).to_have_count(4)
    expect(page.locator(".load-error")).to_have_count(0)
    assert_layout(page)
    screenshot(page, "desktop-zh")

    page.set_viewport_size({"width": 390, "height": 844})
    assert_layout(page)
    screenshot(page, "mobile-zh")
    page.locator(".mod").last.scroll_into_view_if_needed()
    screenshot(page, "mobile-zh-bottom")
    page.locator(".modules-page").evaluate("element => element.closest('#main-content').scrollTop = 0")

    state["read_error"] = "Fixture refresh failure"
    page.get_by_role("button", name="刷新", exact=True).click()
    expect(page.locator(".load-error")).to_contain_text("正在显示上次读取的内容")
    expect(page.locator(".mod")).to_have_count(4)
    assert_layout(page)
    screenshot(page, "refresh-error-zh")
    state["read_error"] = ""
    page.get_by_role("button", name="重试", exact=True).click()
    expect(page.locator(".load-error")).to_have_count(0)

    page.set_viewport_size({"width": 1440, "height": 1000})
    chat = page.locator(".mod").filter(has=page.get_by_role("heading", name="AI 聊天", exact=True))
    writes_before = len(state["writes"])
    chat.get_by_role("button", name="禁用", exact=True).click()
    dialog = page.get_by_role("dialog")
    expect(dialog).to_contain_text("禁用模块")
    assert_controls_locked(page)
    screenshot(page, "disable-confirm-zh")
    dialog.get_by_role("button", name="取消", exact=True).click()
    expect(dialog).not_to_be_visible()
    expect(chat.get_by_role("button", name="重载", exact=True)).to_be_enabled()
    assert len(state["writes"]) == writes_before, "Cancel sent a mutation"

    state["reject_write"] = True
    chat.get_by_role("button", name="重载", exact=True).click()
    expect(page.locator(".el-message--error")).to_contain_text("Fixture rejected module action")
    expect(page.locator(".el-message--success")).to_have_count(0)
    expect(chat.get_by_role("button", name="重载", exact=True)).to_be_enabled()

    state["hold_write"] = True
    state["hold_modules"] = True
    state["hold_panels"] = True
    chat.get_by_role("button", name="重载", exact=True).click()
    pending_write = wait_pending(page, "pending_write")
    assert_controls_locked(page)
    apply_write(pending_write)
    pending_modules = wait_pending(page, "pending_modules")
    assert_controls_locked(page)
    pending_modules.fulfill(json=modules())
    pending_panels = wait_pending(page, "pending_panels")
    assert_controls_locked(page)
    pending_panels.fulfill(json=[])
    expect(chat.get_by_role("button", name="重载", exact=True)).to_be_enabled()

    state["reject_panels"] = True
    protection = page.locator(".mod").filter(has=page.get_by_role("heading", name="领地保护", exact=True))
    protection.get_by_role("button", name="启用", exact=True).click()
    expect(page.locator(".el-message--warning")).to_contain_text("菜单更新失败")
    expect(protection.get_by_role("button", name="重载", exact=True)).to_be_enabled()
    expect(protection.locator(".el-tag")).to_have_text("已启用")
    screenshot(page, "menu-refresh-warning-zh")
    panels_before = state["panels_reads"]
    protection.get_by_role("button", name="重载", exact=True).click()
    expect(protection.get_by_role("button", name="重载", exact=True)).to_be_enabled()
    page.wait_for_function("!document.querySelector('.modules-page .ops button:disabled')")
    assert state["panels_reads"] > panels_before, "Menu retry was not attempted"

    state["language"] = "en"
    page.evaluate("localStorage.setItem('lc-locale','en_US')")
    page.reload()
    expect(page.locator(".page-title")).to_have_text("Modules")
    expect(page.locator(".mod")).to_have_count(4)
    expect(page.get_by_role("tab", name="Module market", exact=True)).to_be_visible()
    assert_layout(page)
    screenshot(page, "desktop-en")
    page.set_viewport_size({"width": 390, "height": 844})
    assert_layout(page)
    screenshot(page, "mobile-en")
    page.locator(".mod").last.scroll_into_view_if_needed()
    screenshot(page, "mobile-en-bottom")

    assert not errors, errors
    assert not unknown, unknown
    assert len(state["writes"]) == 4, state["writes"]
    print("PASS: isolated browser fixtures; desktop/mobile; Chinese/English; long content wrapping; "
          "initial failure/retry; stale list preservation; confirmation cancellation; operation locks; "
          "ok:false errors; menu warning/recovery; no page errors")
    print("Screenshots: " + str(output / "modules-*.png"))
    context.close()
    browser.close()
