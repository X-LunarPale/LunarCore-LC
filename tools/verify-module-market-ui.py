"""Check module market controls and layouts using isolated API fixtures."""
import argparse
from copy import deepcopy
import json
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import expect, sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://127.0.0.1:5216")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = root / "artifacts/module-market-ui"
output.mkdir(parents=True, exist_ok=True)
entries = json.loads((root / "module-market/catalog.json").read_text(encoding="utf-8"))
for entry in entries:
    entry.update(version="0.0.6-preview", installedVersion="", updateAvailable=False)
entries[0].update(installedVersion="alpha2", updateAvailable=True)
state = {"pending": [], "writes": [], "reads": 0, "permissions": ["*"], "downloading": False}
errors = []
unknown = []


def respond(route):
    request = route.request
    path = urlparse(request.url).path
    if path == "/api/auth/info":
        body = {"brand": "LunarCore 琉月", "version": "0.0.6-preview", "ready": True}
    elif path == "/api/auth/me":
        body = {"username": "fixture", "role": "admin", "permissions": state["permissions"]}
    elif path == "/api/panels":
        body = []
    elif path == "/api/modules":
        body = {"items": [{"id": "essentials", "name": "基础命令", "version": "0.0.6-preview",
                           "enabled": True, "depends": [], "softDepends": []}]}
    elif path == "/api/market":
        state["reads"] += 1
        body = {"items": deepcopy(entries), "pending": state["pending"],
                "state": "downloading" if state["downloading"] else "ready" if state["pending"] else "idle",
                "error": "", "current": "chat" if state["downloading"] else "", "version": "0.0.6-preview"}
    elif path == "/api/market/pending" and request.method == "DELETE":
        state["writes"].append(("cancel", ""))
        state["pending"] = []
        body = {"ok": True}
    elif path.startswith("/api/market/") and request.method == "POST":
        module, action = path.split("/")[-2:]
        state["writes"].append((action, module))
        ids = ["land", "landmarks"] if module == "landmarks" else [module]
        state["pending"] = [{"id": i, "asset": None if action == "remove" else f"lbr-{i}.jar"} for i in ids]
        body = {"ok": True}
    else:
        unknown.append(path)
        route.fulfill(status=404, json={"error": path})
        return
    route.fulfill(json=body)


with sync_playwright() as driver:
    browser = driver.chromium.launch(
        executable_path="C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", headless=True)
    context = browser.new_context(viewport={"width": 1440, "height": 1000})
    context.add_init_script("localStorage.setItem('lbr-token','fixture-session')")
    context.route("**/api/**", respond)
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(args.base + "/modules")
    page.get_by_role("tab", name="模块市场", exact=True).click()
    expect(page.locator(".market-row")).to_have_count(19)
    expect(page.locator(".market-version")).to_have_text("v0.0.6 测试预览版")
    page.screenshot(path=str(output / "market-desktop.png"), full_page=True, animations="disabled")
    page.locator(".market-toolbar .el-select").click()
    page.get_by_role("option", name="保护", exact=True).click()
    expect(page.locator(".market-row")).to_have_count(5)
    page.locator(".market-toolbar .el-select").click()
    page.get_by_role("option", name="全部分类", exact=True).click()
    expect(page.locator(".market-row")).to_have_count(19)
    search = page.locator(".market-toolbar input").first
    search.fill("landmarks")
    expect(page.locator(".market-row")).to_have_count(1)
    expect(page.locator(".market-description")).to_contain_text("领地")
    page.get_by_role("button", name="安装", exact=True).click()
    expect(page.get_by_text("等待重启", exact=True)).to_be_visible()
    expect(page.locator(".pending")).to_contain_text("领地")
    expect(page.get_by_role("button", name="安装", exact=True)).to_be_disabled()
    page.get_by_role("button", name="取消变更", exact=True).click()
    expect(page.locator(".pending")).to_have_count(0)
    assert state["writes"][-1] == ("cancel", "")
    search.fill("economy")
    page.get_by_role("button", name="更新", exact=True).click()
    expect(page.get_by_text("等待重启", exact=True)).to_be_visible()
    page.get_by_role("button", name="取消变更", exact=True).click()
    expect(page.locator(".pending")).to_have_count(0)
    page.get_by_role("button", name="卸载", exact=True).click()
    expect(page.get_by_role("dialog")).to_contain_text("模块配置和数据会保留")
    page.get_by_role("button", name="确定", exact=True).click()
    expect(page.locator(".pending")).to_contain_text("卸载")
    page.get_by_role("button", name="取消变更", exact=True).click()
    expect(page.locator(".pending")).to_have_count(0)
    search.fill("")
    page.set_viewport_size({"width": 390, "height": 844})
    page.locator(".el-message").last.wait_for(state="hidden")
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2")
    for row in page.locator(".market-row").all():
        box = row.bounding_box()
        assert box["x"] >= 0 and box["x"] + box["width"] <= 392
    page.screenshot(path=str(output / "market-mobile.png"), full_page=True, animations="disabled")
    page.evaluate("localStorage.setItem('lbr-locale','en_US')")
    page.reload()
    page.get_by_role("tab", name="Module market", exact=True).click()
    expect(page.locator(".market-row")).to_have_count(19)
    expect(page.locator(".market-version")).to_have_text("v0.0.6 Test Preview")
    expect(page.locator(".market-name").first).to_contain_text("Economy")
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2")
    page.screenshot(path=str(output / "market-mobile-en.png"), full_page=True, animations="disabled")

    state["downloading"] = True
    page.get_by_role("button", name="Refresh", exact=True).click()
    expect(page.get_by_text("Downloading modules", exact=False)).to_be_visible()
    page.get_by_role("tab", name="Installed", exact=True).click()
    reads = state["reads"]
    page.wait_for_timeout(2500)
    assert state["reads"] == reads, "Market kept polling after unmount"
    state["downloading"] = False

    state["permissions"] = ["core.modules.view"]
    page.reload()
    page.get_by_role("tab", name="Module market", exact=True).click()
    expect(page.locator(".market-row")).to_have_count(19)
    expect(page.locator(".market-actions")).to_have_count(0)
    assert not errors, errors
    assert not unknown, unknown
    assert ("install", "landmarks") in state["writes"]
    assert ("install", "economy") in state["writes"]
    assert ("remove", "economy") in state["writes"]
    print("PASS: catalog, dependencies, install/update/remove/cancel, restart state, permissions, "
          "poll cleanup, Chinese/English and desktop/mobile layouts")
    context.close()
    browser.close()
