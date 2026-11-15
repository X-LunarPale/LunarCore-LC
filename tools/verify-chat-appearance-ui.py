"""Verify chat appearance in the real UI with local mocked APIs."""
import json
import os
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


BASE = os.environ.get("LC_UI_BASE", "http://127.0.0.1:5216")
OUTPUT = Path(__file__).resolve().parents[1] / "artifacts" / "usability-review-20261115"
EDGE = "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"


def field(key, label, kind, group=None, **metadata):
    result = {"key": key, "label": label, "type": kind, **metadata}
    if group:
        result["group"] = group
    return result


FIELDS = [
    field("antiSpam", "启用反刷屏", "bool", "聊天过滤"),
    field("cooldownMs", "发言间隔(毫秒)", "number", "聊天过滤"),
    field("antiAd", "广告拦截", "bool", "聊天过滤"),
    field("badWords", "敏感词（每行一个）", "textarea", "聊天过滤"),
    field("logEnabled", "记录聊天到数据库", "bool", "聊天过滤"),
    field("announcer", "启用自动公告", "bool", "公告与欢迎"),
    field("announceInterval", "公告间隔(秒)", "number", "公告与欢迎"),
    field("announcements", "公告内容（每行一条，MiniMessage）", "textarea", "公告与欢迎"),
    field("motd", "登录欢迎消息（每行一条）", "textarea", "公告与欢迎"),
    field("defaultChatFormat", "默认玩家聊天格式", "text", "聊天外观",
          help="{world} 世界显示名、{title} 当前称号、<displayname> 玩家显示名、<message> 消息；{world_name} 保留原始世界名。"),
    field("chatShowTitle", "聊天显示当前称号", "bool", "聊天外观"),
    field("worldDefaultColor", "默认世界颜色", "text", "聊天外观"),
    field("worldBrackets", "世界名称自带同色方括号", "bool", "聊天外观"),
    field("worldAliases", "世界显示名称", "world-aliases"),
    field("titlePlaceholder", "PlayerTitle 占位符", "text", "头顶称号"),
    field("nameplateEnabled", "显示头顶称号", "bool", "头顶称号"),
    field("nameplateHeight", "称号高度（格）", "number", "头顶称号", min=2.65, max=8, step=0.05),
    field("nameplateViewRange", "称号可见距离（格）", "number", "头顶称号", min=4, max=64, step=1),
    field("tabEnabled", "启用 TAB", "bool", "玩家列表"),
    field("tabRefresh", "TAB 刷新秒数（1–3600）", "number", "玩家列表", min=1, max=3600, step=1),
    field("tabHeader", "玩家列表顶部文字（每行一条）", "textarea", "玩家列表"),
    field("tabFooter", "玩家列表底部文字（每行一条）", "textarea", "玩家列表"),
    field("tabShowTitle", "玩家名称前显示当前称号", "bool", "玩家列表"),
    field("tabName", "玩家列表名称格式", "text", "玩家列表",
          help="{title} 表示当前称号，{player} 表示玩家名称；未佩戴时不显示称号。检测到其他玩家列表插件或外部修改时让出对应显示。"),
]
PANELS = [{
    "namespace": "chat", "id": "chat", "title": "聊天管理", "icon": "message-circle",
    "group": "玩家", "permission": "chat.view",
    "sections": [{
        "type": "form", "title": "聊天设置", "loadEndpoint": "config",
        "saveEndpoint": "config", "savePermission": "chat.manage", "fields": FIELDS,
    }],
}]
INITIAL = {
    "antiSpam": True, "cooldownMs": 1500, "antiAd": True, "badWords": "fixture-filter",
    "logEnabled": True, "announcer": True, "announceInterval": 300,
    "announcements": "<gold>[公告]</gold> <white>欢迎来到梦幻生存。",
    "motd": "<green>欢迎回来，{player}！</green>",
    "defaultChatFormat": "{world} {title} <hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '><white><displayname></white></click></hover><aqua> » </aqua><white><message>",
    "chatShowTitle": False, "worldDefaultColor": "#55ffff", "worldBrackets": True,
    "worldAliases": [
        {"world": "survival", "name": "梦幻生存", "color": "#55ff55"},
        {"world": "survival_nether", "name": "下界", "color": "#ff5555"},
    ],
    "titlePlaceholder": "%playerTitle_use%", "nameplateEnabled": True,
    "nameplateHeight": 2.65, "nameplateViewRange": 32,
    "tabEnabled": True, "tabRefresh": 5, "tabHeader": "<white>琉月 · 梦幻生存</white>",
    "tabFooter": "<aqua>在线 {online}/{max_players}</aqua>",
    "tabShowTitle": False, "tabName": "{world} {title} <aqua>{player}</aqua>",
}


def no_overflow(page):
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2"), page.viewport_size
    for selector in ("#main-content", ".form-surface", ".chat-appearance", ".preview-surface"):
        fits = all(page.locator(selector).evaluate_all(
            "elements => elements.map(element => element.scrollWidth <= element.clientWidth + 2)"
        ))
        if not fits:
            page.screenshot(path=str(OUTPUT / "chat-overflow.png"), animations="disabled")
            details = page.locator(selector).evaluate_all("""elements => elements.map(element => ({
                class: element.className, width: element.clientWidth, scroll: element.scrollWidth,
                overflow: [...element.querySelectorAll('*')].filter(child => {
                    const rect = child.getBoundingClientRect()
                    return rect.width && rect.right > element.getBoundingClientRect().right + 2
                }).slice(0, 12).map(child => ({ tag: child.tagName, class: child.className,
                    right: child.getBoundingClientRect().right, width: child.getBoundingClientRect().width }))
            }))""")
            raise AssertionError((page.viewport_size, selector, details))


def control(page, key):
    metadata = next((entry for entry in FIELDS if entry["key"] == key), None)
    if metadata and metadata.get("group"):
        open_group(page, metadata["group"])
    return page.locator(f'[data-field-key="{key}"]')


GROUPS_EN = {
    "聊天过滤": "Chat filtering", "公告与欢迎": "Announcements and welcome",
    "聊天外观": "Chat appearance", "头顶称号": "Overhead titles",
    "玩家列表": "Player list", "世界与预览": "Worlds and preview",
}


def open_group(page, name):
    label = GROUPS_EN[name] if page.locator("html").get_attribute("lang") == "en" else name
    page.locator(".form-groups").get_by_role("button", name=label, exact=True).click()


def switch(page, key):
    return control(page, key).get_by_role("switch")


def toggle(page, key):
    control(page, key).locator(".el-switch").click()


def preview(page, label):
    open_group(page, "世界与预览")
    return page.locator(".chat-appearance").get_by_role("group", name=label, exact=True).locator(".preview-surface")


def check_world_color(surface, text, color):
    span = surface.locator("span").filter(has_text=text)
    expect(span).to_have_count(1)
    expect(span).to_have_text(text)
    expect(span).to_have_css("color", color)


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(executable_path=EDGE, headless=True)
    contexts, errors, checks = [], [], []
    OUTPUT.mkdir(parents=True, exist_ok=True)

    def open_page(fail_load=False):
        state = {"settings": deepcopy(INITIAL), "writes": [], "reads": 0,
                 "fail_load": fail_load, "pending_save": None, "unmocked": [], "blocked": []}
        context = browser.new_context(viewport={"width": 1440, "height": 1040})
        contexts.append(context)
        context.add_init_script(
            "localStorage.setItem('lc-token','fixture-token');"
            "localStorage.setItem('lc-locale','zh_CN');"
            "localStorage.setItem('lc-theme','light');"
        )

        def respond(route):
            request = route.request
            parsed = urlparse(request.url)
            if f"{parsed.scheme}://{parsed.netloc}" != BASE:
                state["blocked"].append(request.url)
                route.abort()
                return
            path = parsed.path
            if not path.startswith("/api/"):
                route.continue_()
                return
            if path == "/api/auth/me":
                body = {"username": "fixture", "role": "admin",
                        "permissions": ["chat.view", "chat.manage"]}
            elif path == "/api/auth/info":
                body = {"brand": "LunarCore", "gameLogin": False}
            elif path == "/api/panels":
                body = PANELS
            elif path == "/api/ext/chat/config":
                if request.method == "GET":
                    state["reads"] += 1
                    if state["fail_load"]:
                        route.fulfill(status=503, json={"error": "测试读取失败"})
                        return
                    body = deepcopy(state["settings"])
                elif request.method == "POST":
                    state["writes"].append(deepcopy(request.post_data_json))
                    state["pending_save"] = route
                    return
                else:
                    raise AssertionError(request.method)
            else:
                state["unmocked"].append(f"{request.method} {path}")
                route.fulfill(status=404, json={"error": "Unexpected local fixture request"})
                return
            route.fulfill(json=body)

        context.route("**/*", respond)
        context.route_web_socket("**/api/ws**", lambda connection: connection.on_message(lambda _: None))
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(BASE + "/panel/chat/chat", wait_until="networkidle")
        return page, state

    try:
        page, state = open_page()
        form = page.locator(".panel-form")
        expect(form).to_be_visible()
        expect(page.locator(".world-row")).to_have_count(2)
        expect(page.locator(".form-groups button")).to_have_text(
            ["聊天过滤", "公告与欢迎", "聊天外观", "头顶称号", "玩家列表", "世界与预览"])
        expect(switch(page, "chatShowTitle")).not_to_be_checked()
        expect(switch(page, "tabShowTitle")).not_to_be_checked()
        expect(switch(page, "nameplateEnabled")).to_be_checked()
        expect(preview(page, "聊天预览")).not_to_contain_text("旅行者")
        expect(preview(page, "TAB 玩家名称预览")).not_to_contain_text("旅行者")
        expect(preview(page, "头顶称号预览")).to_have_text("[旅行者] Steve")
        assert preview(page, "头顶称号预览").inner_text() == "[旅行者]\nSteve"
        check_world_color(preview(page, "聊天预览"), "[梦幻生存]", "rgb(85, 255, 85)")
        check_world_color(preview(page, "TAB 玩家名称预览"), "[梦幻生存]", "rgb(85, 255, 85)")
        checks.append("Title-free chat/TAB and the independent overhead title render together; world brackets share the alias color.")

        toggle(page, "chatShowTitle")
        expect(preview(page, "聊天预览")).to_contain_text("旅行者")
        toggle(page, "chatShowTitle")
        toggle(page, "tabShowTitle")
        expect(preview(page, "TAB 玩家名称预览")).to_contain_text("旅行者")
        toggle(page, "tabShowTitle")
        toggle(page, "nameplateEnabled")
        open_group(page, "世界与预览")
        expect(page.get_by_text("头顶称号已关闭", exact=True)).to_be_visible()
        expect(page.locator(".nameplate-preview")).to_have_count(0)
        toggle(page, "nameplateEnabled")
        toggle(page, "worldBrackets")
        check_world_color(preview(page, "聊天预览"), "梦幻生存", "rgb(85, 255, 85)")
        toggle(page, "worldBrackets")

        open_group(page, "世界与预览")
        row = page.locator(".world-row").first
        row.get_by_role("textbox", name="显示名称", exact=True).fill("梦幻家园")
        row.get_by_role("textbox", name="颜色值", exact=True).fill("#12ab34")
        check_world_color(preview(page, "聊天预览"), "[梦幻家园]", "rgb(18, 171, 52)")
        expect(row.locator(".el-color-picker__color-inner")).to_have_css("background-color", "rgb(18, 171, 52)")
        control(page, "nameplateHeight").locator("input[type=number]").fill("3")
        control(page, "nameplateViewRange").locator("input[type=number]").fill("40")
        checks.append("Appearance switches, alias edits and swatches update their previews; bounded title settings remain editable.")

        page.get_by_role("button", name="保存设置", exact=True).click()
        expect(control(page, "nameplateHeight").locator("input[type=number]")).to_be_disabled()
        expect(control(page, "nameplateViewRange").locator("input[type=range]")).to_be_disabled()
        open_group(page, "世界与预览")
        expect(row.get_by_role("textbox", name="显示名称", exact=True)).to_be_disabled()
        expect(switch(page, "worldBrackets")).to_be_disabled()
        assert state["pending_save"] is not None
        assert len(state["writes"]) == 1
        body = state["writes"][0]
        assert set(body) == set(INITIAL), (set(body), set(INITIAL))
        assert {key: body[key] for key in ("worldBrackets", "nameplateEnabled", "nameplateHeight", "nameplateViewRange")} == {
            "worldBrackets": True, "nameplateEnabled": True, "nameplateHeight": 3, "nameplateViewRange": 40}
        assert body["worldAliases"][0] == {"world": "survival", "name": "梦幻家园", "color": "#12ab34"}
        for key in set(INITIAL) - {"worldAliases", "nameplateHeight", "nameplateViewRange"}:
            assert body[key] == INITIAL[key], key
        state["settings"].update(deepcopy(body))
        state["pending_save"].fulfill(json={"ok": True, "message": "已保存并应用聊天设置"})
        state["pending_save"] = None
        expect(page.get_by_role("button", name="保存设置", exact=True)).to_be_disabled()
        expect(control(page, "nameplateHeight").locator("input[type=number]")).to_be_enabled()
        open_group(page, "世界与预览")
        expect(row.get_by_role("textbox", name="显示名称", exact=True)).to_have_value("梦幻家园")
        expect(control(page, "antiSpam").get_by_role("switch")).to_be_checked()
        expect(page.locator(".el-message")).to_have_count(0, timeout=6000)
        checks.append("Save includes all current fields and the four added settings, preserves unrelated values, and disables editing in flight.")

        for language, labels in [
            ("zh", ("聊天预览", "TAB 玩家名称预览", "头顶称号预览")),
            ("en", ("Chat preview", "TAB player name preview", "Overhead title preview")),
        ]:
            if language == "en":
                page.get_by_role("button", name="语言", exact=True).click()
                page.get_by_role("menuitem", name="English", exact=True).click()
                expect(page.locator(".form-groups button")).to_have_text(
                    ["Chat filtering", "Announcements and welcome", "Chat appearance", "Overhead titles", "Player list", "Worlds and preview"])
            for width, name in [(1440, "desktop"), (390, "mobile")]:
                page.set_viewport_size({"width": width, "height": 1040 if width == 1440 else 844})
                no_overflow(page)
                expect(preview(page, labels[0])).not_to_contain_text("旅行者")
                expect(preview(page, labels[1])).not_to_contain_text("旅行者")
                expect(preview(page, labels[2])).to_contain_text("旅行者")
                page.mouse.move(width - 8, 8)
                open_group(page, "聊天外观")
                page.get_by_role("group", name="聊天外观" if language == "zh" else "Chat appearance", exact=True).evaluate(
                    "element => element.scrollIntoView({block:'start'})")
                page.screenshot(path=str(OUTPUT / f"chat-{name}-settings-{language}.png"), animations="disabled")
                open_group(page, "世界与预览")
                page.locator(".chat-appearance" if width == 1440 else ".format-preview").evaluate(
                    "element => element.scrollIntoView({block:'start'})")
                page.screenshot(path=str(OUTPUT / f"chat-{name}-{language}.png"), animations="disabled")
                page.locator("#main-content").evaluate("element => element.scrollTop = element.scrollHeight")
                page.screenshot(path=str(OUTPUT / f"chat-{name}-preview-{language}.png"), animations="disabled")
        checks.append("1440px and 390px layouts render Chinese and English without horizontal overflow.")

        failed, failure = open_page(fail_load=True)
        expect(failed.get_by_text("测试读取失败", exact=False)).to_be_visible()
        expect(failed.locator(".panel-form")).to_have_count(0)
        expect(failed.get_by_role("button", name="保存设置", exact=True)).to_have_count(0)
        assert not failure["writes"]
        failed.screenshot(path=str(OUTPUT / "chat-read-failure.png"), full_page=True, animations="disabled")
        failure["fail_load"] = False
        failed.get_by_role("button", name="重新加载", exact=True).click()
        expect(failed.locator(".world-row")).to_have_count(2)
        expect(control(failed, "nameplateHeight").locator("input[type=number]")).to_have_value("2.65")
        expect(failed.get_by_role("button", name="保存设置", exact=True)).to_be_disabled()
        expect(failed.get_by_text("测试读取失败", exact=False)).to_have_count(0)
        assert not failure["writes"]
        checks.append("A failed initial read exposes no saveable defaults; retry restores the real configuration.")

        assert not errors, errors
        assert not state["unmocked"] and not failure["unmocked"], (state["unmocked"], failure["unmocked"])
        assert not state["blocked"] and not failure["blocked"], (state["blocked"], failure["blocked"])
        print("PASS chat appearance UI verification:")
        for check in checks:
            print("- " + check)
        print("- All APIs were mocked; no remote requests or server writes occurred.")
    finally:
        for context in contexts:
            context.close()
        browser.close()
