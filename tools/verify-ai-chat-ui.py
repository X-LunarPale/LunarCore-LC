"""Exercise AI settings against mocked APIs without writing to the server."""
import json
import re
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


BASE = "http://127.0.0.1:5197"
OUTPUT = Path(__file__).resolve().parents[1] / "artifacts" / "ai-chat-ui"
OUTPUT.mkdir(parents=True, exist_ok=True)
SECRET = "fixture-private-key-must-not-reach-browser"
PANELS = [
    {
        "namespace": "chat", "id": "ai", "title": "AI 聊天",
        "group": "玩家", "icon": "bot", "permission": "chat.ai.manage",
        "sections": [{"type": "text", "title": "配置", "markdown": "AI 聊天配置"}],
    },
    {
        "namespace": "chat", "id": "chat", "title": "聊天管理",
        "group": "玩家", "icon": "message-circle", "permission": "chat.view",
        "sections": [{"type": "text", "title": "聊天记录", "markdown": "模拟公开聊天记录"}],
    },
]
INITIAL = {
    "enabled": True, "name": "月灵", "persona": "友善、自然且简短地回应玩家。",
    "baseUrl": "https://provider.example/v1", "model": "fixture-chat",
    "replyMode": "mention", "proactiveProbability": 100,
    "cooldownSeconds": 20, "keyConfigured": True,
}


def no_overflow(page):
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2"), (
        f"Horizontal page overflow at {page.viewport_size}"
    )
    assert page.locator(".ai-workspace").evaluate(
        "element => element.scrollWidth <= element.clientWidth + 2"
    ), "AI workspace overflows"


def no_secret(page):
    expect(page.locator("#ai-key")).to_have_value("")
    assert SECRET not in page.content()
    assert SECRET not in page.locator("body").inner_text()


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        executable_path="C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
        headless=True,
    )
    context = browser.new_context(viewport={"width": 1440, "height": 1040})
    context.add_init_script(
        "localStorage.setItem('lc-token', 'fixture-token');"
        "localStorage.setItem('lc-locale', 'zh_CN');"
        "localStorage.setItem('lc-nav-open-groups', '[\"player-services\"]');"
    )
    state = {"settings": deepcopy(INITIAL), "writes": [], "reads": 0, "unmocked": []}
    results = []
    errors = []

    def respond(route):
        request = route.request
        path = urlparse(request.url).path
        if path == "/api/auth/me":
            body = {
                "username": "fixture", "role": "admin",
                "permissions": ["chat.ai.manage", "chat.view"],
            }
        elif path == "/api/auth/info":
            body = {"brand": "LunarCore", "gameLogin": False}
        elif path == "/api/panels":
            body = PANELS
        elif path == "/api/ext/chat/ai/settings":
            if request.method == "GET":
                state["reads"] += 1
            elif request.method == "POST":
                data = request.post_data_json
                state["writes"].append(data)
                state["settings"].update({
                    key: value for key, value in data.items()
                    if key not in ("apiKey", "clearApiKey")
                })
                if data.get("apiKey"):
                    state["settings"]["keyConfigured"] = True
                elif data.get("clearApiKey"):
                    state["settings"]["keyConfigured"] = False
            else:
                raise AssertionError(f"Unexpected settings method: {request.method}")
            body = deepcopy(state["settings"])
            assert "apiKey" not in body
        else:
            state["unmocked"].append(f"{request.method} {path}")
            route.fulfill(status=404, json={"error": "Unmocked fixture request"})
            return
        route.fulfill(json=body)

    context.route("**/api/**", respond)
    context.route_web_socket("**/api/ws**", lambda socket: socket.on_message(lambda message: None))
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    try:
        page.goto(f"{BASE}/panel/chat/ai", wait_until="networkidle")
        expect(page.get_by_role("heading", name="AI 聊天", exact=True)).to_be_visible()
        expect(page.get_by_text("配置已加载", exact=True)).to_be_visible()
        expect(page.get_by_text("对话已开启", exact=True)).to_be_visible()
        expect(page.get_by_text("已保存密钥（不会返回浏览器）", exact=False)).to_be_visible()
        expect(page.locator("#ai-proactive-probability")).to_have_count(0)
        no_secret(page)
        results.append("Mention mode hides proactive probability; saved API key is never returned.")

        page.locator("#ai-mode").focus()
        page.locator("#ai-mode").press("ArrowDown")
        page.get_by_role("option", name="允许主动回复", exact=True).click()
        expect(page.get_by_role("slider", name="主动回复概率")).to_be_visible()
        probability = page.locator("#ai-proactive-probability")
        expect(probability).to_be_visible()
        expect(probability).to_have_value("100")
        probability.fill("0")
        probability.press("Tab")
        expect(page.get_by_role("slider", name="主动回复概率")).to_have_attribute("aria-valuenow", "0")
        probability.fill("100")
        probability.press("Tab")
        expect(page.get_by_role("slider", name="主动回复概率")).to_have_attribute("aria-valuenow", "100")
        probability.fill("25")
        probability.press("Tab")
        expect(page.get_by_role("slider", name="主动回复概率")).to_have_attribute("aria-valuenow", "25")
        results.append("All mode shows slider and exact numeric input; 0, 25 and 100 stay synchronized.")

        reads_before_save = state["reads"]
        page.get_by_role("button", name="保存 AI 配置", exact=True).click()
        expect(page.get_by_text("配置已保存", exact=True)).to_be_visible()
        assert len(state["writes"]) == 1
        assert state["writes"][0]["replyMode"] == "all"
        assert state["writes"][0]["proactiveProbability"] == 25
        assert "apiKey" not in state["writes"][0]
        assert state["reads"] > reads_before_save
        expect(probability).to_have_value("25")
        expect(page.get_by_role("button", name="保存 AI 配置", exact=True)).to_be_disabled()
        expect(page.get_by_text("保存后立即生效；首次安装新版插件需重启服务器。", exact=True)).to_be_visible()
        no_secret(page)
        results.append("Save posts proactiveProbability=25, reads settings back, and clears the dirty state.")

        page.locator("#ai-name").fill("月灵伙伴")
        page.get_by_role("menuitem", name="聊天管理", exact=True).click()
        discard = page.get_by_role("dialog", name="修改尚未保存", exact=True)
        expect(discard).to_be_visible()
        page.screenshot(path=str(OUTPUT / "unsaved-confirmation.png"), full_page=True, animations="disabled")
        discard.get_by_role("button", name="继续编辑", exact=True).click()
        expect(discard).to_be_hidden()
        expect(page).to_have_url(f"{BASE}/panel/chat/ai")
        expect(page.locator("#ai-name")).to_have_value("月灵伙伴")
        expect(page.get_by_role("menuitem", name="AI 聊天", exact=True)).to_have_class(
            re.compile(r"\bis-active\b"))
        assert len(state["writes"]) == 1
        results.append("Cancelling navigation to another /panel route preserves both route and unsaved name.")

        page.locator("#ai-name").fill(INITIAL["name"])
        page.locator(".ai-heading").scroll_into_view_if_needed()
        page.mouse.move(1360, 1000)
        no_overflow(page)
        page.screenshot(path=str(OUTPUT / "desktop-light.png"), full_page=True, animations="disabled")
        page.get_by_role("button", name="切换到暗色", exact=True).click()
        page.mouse.move(1360, 1000)
        page.wait_for_timeout(300)
        no_overflow(page)
        page.screenshot(path=str(OUTPUT / "desktop-dark.png"), full_page=True, animations="disabled")

        page.set_viewport_size({"width": 390, "height": 844})
        page.locator(".ai-heading").scroll_into_view_if_needed()
        no_overflow(page)
        no_secret(page)
        page.screenshot(path=str(OUTPUT / "mobile-dark.png"), full_page=True, animations="disabled")
        page.get_by_role("button", name="切换到亮色", exact=True).click()
        page.mouse.move(370, 800)
        page.wait_for_timeout(300)
        no_overflow(page)
        page.screenshot(path=str(OUTPUT / "mobile-light.png"), full_page=True, animations="disabled")
        page.locator(".probability-control").scroll_into_view_if_needed()
        page.screenshot(path=str(OUTPUT / "mobile-probability.png"), full_page=True, animations="disabled")
        results.append("1440px and 390px layouts in light and dark themes have no horizontal overflow.")

        assert not errors, errors
        assert not state["unmocked"], state["unmocked"]
        results.append("No page errors or unmocked API requests; no real service writes occurred.")
        report = {
            "mode": "mock-api", "results": results, "writes": state["writes"],
            "settingsReads": state["reads"], "pageErrors": errors,
        }
        (OUTPUT / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("PASS AI chat UI mock verification:")
        for result in results:
            print(f"- {result}")
    finally:
        context.close()
        browser.close()
