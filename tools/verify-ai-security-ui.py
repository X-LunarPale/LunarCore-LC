"""Exercise AI, TOTP and QQauth controls against isolated API fixtures."""
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import expect, sync_playwright

BASE = "http://127.0.0.1:5197"
OUTPUT = Path(__file__).resolve().parents[1] / "artifacts" / "ai-security-ui"
PANEL = {"namespace": "qqauth", "id": "accounts", "title": "QQ验证与账号认证",
         "permission": "qqauth.view", "icon": "shield", "sections": []}
AI_PANEL = {"namespace": "chat", "id": "ai", "title": "AI 聊天",
            "permission": "chat.ai.manage", "icon": "bot", "sections": []}
CONFIG = {
    "revision": "fixture-revision", "requiresReload": True,
    "configuredSecrets": {"integration.astrbot.token": True, "integration.onebot.token": False},
    "values": {
        "integration.mode": "astrbot", "integration.group-id": "12345678",
        "integration.astrbot.bind-address": "127.0.0.1", "integration.astrbot.port": 12312,
        "integration.astrbot.token": "", "integration.onebot.endpoint": "ws://127.0.0.1:3001",
        "integration.onebot.token": "", "integration.onebot.reconnect-seconds": 5,
        "binding.code-length": 6, "binding.bind-ttl-seconds": 600,
        "binding.max-per-qq": 1, "binding.per-qq-limits": {"12345678": 2},
        "auth.min-password-length": 10, "auth.max-password-length": 64, "auth.bcrypt-cost": 12,
        "auth.sessions.enabled": False, "auth.sessions.ttl-seconds": 86400,
        "auth.restrictions.allow-commands": ["qqauth", "help"],
    },
    "messages": {"login.bind-required": "<aqua>欢迎 {player}</aqua>，请验证 {code}", "login.required": "请先登录"},
}
AI = {
    "enabled": True, "name": "月灵", "persona": "友善地聊天", "baseUrl": "https://api.deepseek.com/v1",
    "model": "deepseek-chat", "fallbackModel": "backup-model", "providerType": "deepseek",
    "keyConfigured": True, "replyMode": "mention", "proactiveProbability": 25, "cooldownSeconds": 20,
    "memoryEnabled": True, "embeddingBaseUrl": "https://embed.example/v1",
    "embeddingModel": "embedding-model", "embeddingKeyConfigured": True,
    "replyOnError": False, "errorMessage": "暂时无法回复",
    "bots": [{"id": "helper", "name": "小梦", "persona": "活泼地聊天", "enabled": True,
              "baseUrl": "https://provider.example/v1", "providerType": "sub2api",
              "model": "chat-model", "fallbackModel": "", "keyConfigured": True}],
}


def no_overflow(page):
    page.wait_for_function("""() => {
      const box = document.querySelector('.aside')?.getBoundingClientRect();
      return !box || (innerWidth < 900 ? box.right <= 1 : box.left >= -1 && box.width >= 247);
    }""")
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2"), page.viewport_size


with sync_playwright() as driver:
    browser = driver.chromium.launch(
        executable_path="C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", headless=True)
    context = browser.new_context(viewport={"width": 1440, "height": 1000})
    context.add_init_script("localStorage.setItem('lc-locale','zh_CN')")
    state = {"enabled": False, "writes": [], "balance_reads": 0}
    errors = []
    unknown = []

    def respond(route):
        request = route.request
        path = urlparse(request.url).path
        if path == "/api/auth/info":
            body = {"brand": "LunarCore", "ready": True, "gameLogin": True}
        elif path in ("/api/auth/login", "/api/auth/game"):
            body = {"totpRequired": True, "challenge": "fixture-pending"}
        elif path == "/api/auth/totp/complete":
            assert request.post_data_json == {"challenge": "fixture-pending", "code": "123456"}
            body = {"token": "fixture-session", "user": {"username": "fixture", "role": "admin", "permissions": ["*"]}}
        elif path == "/api/auth/me":
            body = {"username": "fixture", "role": "admin", "permissions": ["*"], "totpEnabled": state["enabled"]}
        elif path == "/api/auth/totp/setup":
            body = {"secret": "FIXTUREAUTHENTICATORSECRET", "uri": "otpauth://totp/LunarCore:fixture?secret=FIXTURE", "expiresAt": 1}
        elif path == "/api/auth/totp/enable":
            state["enabled"] = True
            body = {"enabled": True, "token": "fixture-updated-session", "recoveryCodes": ["ABCDEF-GHIJKLM", "NOPQRS-TUVWXYZ"]}
        elif path == "/api/panels":
            body = [PANEL, AI_PANEL]
        elif path == "/api/ext/qqauth/stats":
            body = [{"label": "模块状态", "value": "就绪"}, {"label": "账号数", "value": 0}]
        elif path == "/api/ext/qqauth/accounts":
            body = {"items": [], "total": 0}
        elif path == "/api/ext/qqauth/config":
            if request.method == "POST":
                state["writes"].append(request.post_data_json)
            body = deepcopy(CONFIG)
        elif path == "/api/ext/qqauth/messages/preview":
            body = {"plain": "欢迎 Steve，请验证 123456", "formatted": "<aqua>欢迎 Steve</aqua>，请验证 123456"}
        elif path == "/api/ext/chat/ai/settings":
            body = deepcopy(AI)
        elif path == "/api/ext/chat/ai/models":
            body = {"models": ["deepseek-chat", "backup-model"]}
        elif path == "/api/ext/chat/ai/balance":
            state["balance_reads"] += 1
            body = {"available": True, "balances": [{"currency": "CNY", "remaining": "10.00"}], "fetchedAt": 1793577600000}
        else:
            unknown.append(path)
            route.fulfill(status=404, json={"error": path})
            return
        route.fulfill(json=body)

    context.route("**/api/**", respond)
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(BASE + "/login?redirect=/profile")
    page.get_by_label("用户名", exact=True).fill("fixture")
    page.get_by_label("密码", exact=True).fill("fixture-password")
    page.get_by_role("button", name="登 录").click()
    expect(page.get_by_label("动态验证码或恢复码", exact=True)).to_be_visible()
    assert page.evaluate("localStorage.getItem('lc-token')") is None
    page.get_by_label("动态验证码或恢复码", exact=True).fill("123456")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_url(BASE + "/profile")
    assert page.evaluate("localStorage.getItem('lc-token')") == "fixture-session"
    page.goto(BASE + "/profile", wait_until="networkidle")
    page.get_by_label("当前密码", exact=True).fill("fixture-password")
    page.get_by_role("button", name="设置两步验证", exact=True).click()
    expect(page.get_by_label("验证器密钥", exact=True)).to_have_value("FIXTUREAUTHENTICATORSECRET")
    expect(page.get_by_alt_text("验证器绑定二维码")).to_have_attribute("src", __import__("re").compile("^data:image/png"))
    page.get_by_label("动态验证码或恢复码", exact=True).fill("123456")
    page.get_by_role("button", name="确认启用", exact=True).click()
    expect(page.get_by_text("恢复码仅显示一次，请妥善保存")).to_be_visible()
    assert "ABCDEF-GHIJKLM" in page.inner_text("body")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    page.locator(".totp-setting").scroll_into_view_if_needed()
    page.screenshot(path=str(OUTPUT / "totp-desktop.png"), full_page=True, animations="disabled")
    page.set_viewport_size({"width": 390, "height": 844})
    no_overflow(page)
    page.locator(".totp-setting").scroll_into_view_if_needed()
    page.screenshot(path=str(OUTPUT / "totp-mobile.png"), full_page=True, animations="disabled")
    page.get_by_role("button", name="已保存恢复码").click()
    assert "ABCDEF-GHIJKLM" not in page.inner_text("body")
    page.goto(BASE + "/panel/qqauth/accounts", wait_until="networkidle")
    expect(page.get_by_role("heading", name="QQauth 配置")).to_be_visible()
    expect(page.get_by_label("AstrBot 桥接密钥", exact=True)).to_have_value("")
    expect(page.locator(".preview-surface")).to_contain_text("欢迎 Steve")
    page.get_by_role("button", name="预览 QQ 纯文本").click()
    expect(page.locator(".message-body pre")).to_have_text("欢迎 Steve，请验证 123456")
    page.get_by_label("验证 QQ 群号", exact=True).fill("87654321")
    page.get_by_role("button", name="保存设置", exact=True).click()
    expect(page.get_by_text("重载 QQauth 会撤销在线玩家认证并断开连接。")).to_be_visible()
    assert state["writes"][0]["values"]["integration.group-id"] == "87654321"
    assert state["writes"][0]["values"]["integration.astrbot.token"] == ""
    no_overflow(page)
    page.screenshot(path=str(OUTPUT / "qqauth-mobile.png"), full_page=True, animations="disabled")
    page.set_viewport_size({"width": 1440, "height": 1000})
    no_overflow(page)
    page.screenshot(path=str(OUTPUT / "qqauth-desktop.png"), full_page=True, animations="disabled")
    page.goto(BASE + "/panel/chat/ai", wait_until="networkidle")
    expect(page.get_by_text("小梦", exact=True)).to_be_visible()
    page.get_by_role("button", name="获取上游模型").first.click()
    expect(page.locator("#ai-model-list")).to_be_visible()
    page.get_by_role("button", name="查询余额").first.click()
    expect(page.get_by_text("10.00 CNY", exact=False).first).to_be_visible()
    page.get_by_text("每分钟自动查询余额", exact=True).click()
    page.wait_for_function("document.querySelector('.balance-line')")
    page.screenshot(path=str(OUTPUT / "ai-desktop.png"), full_page=True, animations="disabled")
    page.set_viewport_size({"width": 390, "height": 844})
    no_overflow(page)
    page.screenshot(path=str(OUTPUT / "ai-mobile.png"), full_page=True, animations="disabled")
    assert not errors, errors
    assert not unknown, unknown
    print("PASS TOTP pre-auth, enrollment, recovery display; QQauth config and previews; AI models, balances and responsive layouts")
    context.close()
    browser.close()
