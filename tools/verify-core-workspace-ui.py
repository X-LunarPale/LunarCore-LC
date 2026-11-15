"""Check core workspace pages with isolated APIs and the real Vite UI."""
import argparse
import math
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://127.0.0.1:5216")
args = parser.parse_args()
output = Path(__file__).resolve().parents[1] / "artifacts" / "webui-rewrite-20261115"
output.mkdir(parents=True, exist_ok=True)
errors = []
unknown = []
writes = []
whitelist = [{"name": "MoonPlayer", "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "online": True}]
users = [
    {"username": "fixture", "role": "admin", "roleName": "Administrator", "totpEnabled": True,
     "lastLogin": 1794700000000, "created": 1794000000000, "lastIp": "127.0.0.1"},
    {"username": "helper", "role": "helper", "roleName": "Helper", "totpEnabled": False,
     "mcName": "MoonPlayer", "mcUuid": whitelist[0]["uuid"], "lastLogin": 1794600000000,
     "created": 1794000000000, "lastIp": "127.0.0.1"},
]
roles = [
    {"name": "admin", "display": "Administrator", "builtin": True, "permissions": ["*"], "users": 1},
    {"name": "helper", "display": "Helper", "builtin": True,
     "permissions": ["core.dashboard", "core.players.view", "chat.view", "motd.view"], "users": 1},
    {"name": "viewer", "display": "Viewer", "builtin": True, "permissions": ["core.dashboard"], "users": 0},
]
permissions = [
    {"owner": owner, "node": node, "description": description}
    for owner, node, description in [
        ("core", "core.dashboard", "查看服务器运行状态"),
        ("core", "core.players.view", "查看在线与历史玩家"),
        ("core", "core.players.manage", "管理玩家"),
        ("chat", "chat.view", "查看聊天设置"),
        ("chat", "chat.manage", "修改聊天设置"),
        ("motd", "motd.view", "查看维护状态"),
        ("motd", "motd.manage", "管理维护模式"),
    ]
]


def respond(route):
    path = urlparse(route.request.url).path
    method = route.request.method
    if method != "GET":
        writes.append((method, path))
        route.fulfill(json={"ok": True, "message": "Fixture saved"})
        return
    if path == "/api/auth/info":
        body = {"brand": "LunarCore 琉月", "version": "0.0.9-pre.1", "ready": True}
    elif path == "/api/auth/me":
        body = {**users[0], "permissions": ["*"]}
    elif path == "/api/panels":
        body = []
    elif path == "/api/dashboard":
        body = {
            "time": 1794700000000, "uptime": 4523000, "tps": [19.97, 19.92, 19.86],
            "mspt": 18.3, "online": 12, "maxPlayers": 50, "memoryUsed": 2_400_000_000,
            "memoryMax": 8_000_000_000, "cpuProcess": 23.6, "cpuSystem": 31.8, "cores": 8,
            "serverVersion": "Paper 26.3 build 238", "coreVersion": "0.0.9-pre.1",
            "javaVersion": "25.0.1", "os": "Linux", "worldCount": 3, "chunks": 623,
            "entities": 1842, "plugins": 12, "modules": 16,
            "worlds": [
                {"name": "survival", "environment": "NORMAL", "players": 10, "entities": 1462, "chunks": 442},
                {"name": "survival_nether", "environment": "NETHER", "players": 2, "entities": 312, "chunks": 132},
                {"name": "lobby", "environment": "NORMAL", "players": 0, "entities": 68, "chunks": 49},
            ],
            "history": [
                {"tps": 19.5 + math.sin(index / 5) * 0.4, "mspt": 18 + math.sin(index / 6) * 6,
                 "online": 8 + index // 8, "memory": 2_000_000_000 + index * 10_000_000,
                 "cpu": 23 + math.sin(index / 4) * 5}
                for index in range(40)
            ],
        }
    elif path == "/api/server/whitelist":
        body = {"enabled": False, "enforced": True, "items": whitelist}
    elif path == "/api/users":
        body = {"items": users}
    elif path == "/api/roles":
        body = {"items": roles}
    elif path == "/api/permissions":
        body = permissions
    elif path.startswith("/api/config/paper/"):
        scope = path.rsplit("/", 1)[-1]
        body = {"scope": scope, "fingerprint": "fixture", "fields": [
            {"key": "chunk-loading.max-concurrent-sends", "label": "同时发送区块数", "type": "integer",
             "min": 1, "max": 128, "default": 16, "group": "区块加载", "help": "控制每位玩家同时接收的区块数量。"},
            {"key": "collisions.enable-player-collisions", "label": "玩家碰撞", "type": "boolean",
             "default": True, "group": "玩家交互", "help": "允许玩家之间发生碰撞。"},
        ], "values": {"chunk-loading.max-concurrent-sends": 16, "collisions.enable-player-collisions": True}}
    elif path == "/api/config":
        body = {"path": "plugins/LunarCore/config.yml", "content": "language: zh_CN\nweb:\n  port: 12536\n"}
    else:
        unknown.append(path)
        route.fulfill(status=404, json={"error": "Unexpected fixture route: " + path})
        return
    route.fulfill(json=deepcopy(body))


def layout(page):
    failures = page.locator("#main-content").evaluate("""root => {
      const errors = [];
      if (document.documentElement.scrollWidth > innerWidth + 2) errors.push('document overflow');
      if (root.scrollWidth > root.clientWidth + 2) errors.push('workspace overflow');
      for (const button of root.querySelectorAll('.el-button')) {
        if (!button.getClientRects().length) continue;
        if (button.scrollWidth > button.clientWidth + 2) errors.push('button clipping: ' + button.textContent.trim());
      }
      return errors;
    }""")
    assert not failures, failures


def capture(page, name):
    layout(page)
    page.screenshot(path=str(output / (name + ".png")), animations="disabled")


with sync_playwright() as driver:
    browser = driver.chromium.launch(
        executable_path="C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", headless=True
    )
    for language in ("zh_CN", "en_US"):
        context = browser.new_context(viewport={"width": 1440, "height": 1000}, reduced_motion="reduce")
        context.add_init_script(
            f"localStorage.setItem('lc-token','fixture-session');"
            f"localStorage.setItem('lc-locale','{language}');localStorage.setItem('lc-theme','light');"
        )
        context.route("**/api/**", respond)
        context.route_web_socket("**/api/ws**", lambda websocket: websocket.on_message(lambda message: None))
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        for path, selector, name in [
            ("/", ".dashboard-page", "dashboard"),
            ("/server", ".server-page", "server"),
            ("/accounts", ".accounts-page", "accounts"),
            ("/config", ".config-page", "config"),
        ]:
            page.set_viewport_size({"width": 1440, "height": 1000})
            page.goto(args.base + path)
            expect(page.locator(selector)).to_be_visible()
            if name == "dashboard":
                expect(page.locator(".metric")).to_have_count(6)
            elif name == "accounts":
                expect(page.locator("#pane-users .el-table__body-wrapper tbody tr")).to_have_count(2)
            elif name == "config":
                page.get_by_role("tab", name="核心配置" if language == "zh_CN" else "Core Configuration").click()
                expect(page.locator(".editor-body textarea")).to_have_value("language: zh_CN\nweb:\n  port: 12536\n")
            capture(page, f"{name}-desktop-{language}")
            page.set_viewport_size({"width": 390, "height": 844})
            capture(page, f"{name}-mobile-{language}")
            if name == "server":
                page.get_by_role("tab", name="白名单" if language == "zh_CN" else "Whitelist", exact=True).click()
                expect(page.locator(".el-table__body-wrapper tbody tr")).to_have_count(1)
                capture(page, f"whitelist-mobile-{language}")
            if name == "accounts":
                page.get_by_role("tab", name="角色（3）" if language == "zh_CN" else "Roles (3)", exact=True).click()
                page.get_by_role("button", name="新建角色" if language == "zh_CN" else "New Role", exact=True).click()
                dialog = page.get_by_role("dialog")
                expect(dialog).to_be_visible()
                search = dialog.get_by_role("textbox", name="搜索权限" if language == "zh_CN" else "Search permissions", exact=True)
                search.fill("motd.manage")
                expect(dialog.locator(".perm-items .el-checkbox")).to_have_count(1)
                page.screenshot(path=str(output / f"roles-dialog-mobile-{language}.png"), animations="disabled")
                dialog.get_by_role("button", name="取消" if language == "zh_CN" else "Cancel", exact=True).click()
        page.goto(args.base + "/")
        page.locator(".theme-control").click()
        expect(page.locator("html")).to_have_class("dark")
        expect(page.locator(".metric")).to_have_count(6)
        capture(page, f"dashboard-mobile-dark-{language}")
        context.close()
    assert not errors, errors
    assert not unknown, unknown
    assert not writes, writes
    browser.close()
    print("PASS: core workspace fixtures; desktop/mobile; Chinese/English; dark mode; permission filtering; no overflow or runtime errors")
    print("Screenshots: " + str(output))
