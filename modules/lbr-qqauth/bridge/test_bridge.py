"""Local tests; AstrBot is stubbed and no network is used."""
import asyncio
import importlib.util
import json
import pathlib
import socket
import ssl
import sys
import threading
import types
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

api = types.ModuleType("astrbot.api")
api.AstrBotConfig = dict
warnings = []
api.logger = types.SimpleNamespace(warning=warnings.append)
events = types.ModuleType("astrbot.api.event")
events.AstrMessageEvent = object
class Filters:
    PlatformAdapterType = types.SimpleNamespace(QQOFFICIAL=1, QQOFFICIAL_WEBHOOK=2, AIOCQHTTP=4)
    EventMessageType = types.SimpleNamespace(GROUP_MESSAGE=1)
    @staticmethod
    def platform_adapter_type(value):
        def decorate(fn):
            fn.adapter_types = value
            return fn
        return decorate
    @staticmethod
    def event_message_type(*args): return lambda fn: fn
events.filter = Filters
stars = types.ModuleType("astrbot.api.star")
stars.Context = object
class Star:
    def __init__(self, context): pass
stars.Star = Star
for name, module in [("astrbot", types.ModuleType("astrbot")), ("astrbot.api", api),
                     ("astrbot.api.event", events), ("astrbot.api.star", stars)]:
    sys.modules[name] = module
plugin_root = pathlib.Path(__file__).resolve().parent / "astrbot_plugin_qqauth"
spec = importlib.util.spec_from_file_location("qqauth_bridge", plugin_root / "main.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)

class Event:
    def __init__(self, group="g", sender="actual", text="改密 123456"):
        self.group, self.sender, self.message_str, self.stopped = group, sender, text, False
    def get_group_id(self): return self.group
    def get_sender_id(self): return self.sender
    def stop_event(self): self.stopped = True
    def plain_result(self, text): return text

class Tests(unittest.IsolatedAsyncioTestCase):
    def test_command_normalization(self):
        self.assertEqual("/改密 123456", bridge.normalize_command("改密 123456", [("/改密", "改密")]))
        self.assertEqual("/验证 123456", bridge.normalize_command("/验证 123456", [("/验证", "验证")]))
        self.assertEqual("/验证 123456", bridge.normalize_command("绑定 123456", [("/绑定", "验证")]))
        self.assertIsNone(bridge.normalize_command("绑定者 123456", [("/绑定", "验证")]))
        self.assertIsNone(bridge.normalize_command("chat", [("", "验证")]))
        self.assertIsNone(bridge.normalize_command("//验证 123456", [("/验证", "验证")]))

    def test_default_address_matches_server(self):
        schema = json.loads((plugin_root / "_conf_schema.json").read_text(encoding="utf-8"))
        self.assertEqual("http://127.0.0.1:12312/qqauth/group", bridge.DEFAULT_URL)
        self.assertEqual(bridge.DEFAULT_URL, schema["qqauth_url"]["default"])
        self.assertIs(schema["qqauth_token"]["secret"], True)
        self.assertEqual(bridge.DEFAULT_ERROR_REPLY, schema["error_reply"]["default"])
        self.assertEqual(7, bridge.QQAuthBridge.on_group_message.adapter_types)
        server_config = (plugin_root.parents[1] / "src/main/resources/config.yml").read_text(encoding="utf-8")
        self.assertIn("port: 12312", server_config)

    def test_base_address_and_trailing_slash(self):
        for address in ["http://127.0.0.1:12312", "http://127.0.0.1:12312/",
                        "http://127.0.0.1:12312/qqauth/group/"]:
            self.assertEqual(bridge.DEFAULT_URL, bridge.bridge_url(address))
        self.assertEqual("https://bridge.example/proxy/qqauth/group",
                         bridge.bridge_url("https://bridge.example/proxy/qqauth/group"))
        for address in ["localhost:12312", "ws://localhost:12312", "http://user:password@localhost"]:
            with self.assertRaises(ValueError):
                bridge.bridge_url(address)
    async def test_identity_and_reset_reply(self):
        plugin = bridge.QQAuthBridge(None, {"group_id": "g"})
        payloads = []
        def post(payload):
            payloads.append(payload)
            return {"ok": True, "handled": True, "reply": "success"}
        plugin._post_json = post
        event = Event()
        self.assertEqual(["success"], [item async for item in plugin.on_group_message(event)])
        self.assertTrue(event.stopped)
        self.assertEqual({"group_id":"g", "sender_id":"actual", "message":"/改密 123456"}, payloads[0])
        await plugin.terminate()
        self.assertEqual([], [item async for item in plugin.on_group_message(Event())])

    async def test_custom_prefix_is_converted_to_server_command(self):
        plugin = bridge.QQAuthBridge(None, {"group_id": "g", "bind_prefix": "/绑定"})
        payloads = []
        plugin._post_json = lambda payload: (payloads.append(payload) or {"ok": True, "handled": True, "reply": "已绑定"})
        result = [item async for item in plugin.on_group_message(Event(text="绑定 123456"))]
        self.assertEqual(["已绑定"], result)
        self.assertEqual("/验证 123456", payloads[0]["message"])
    async def test_allowlist_and_ordinary_chat(self):
        for config, event in [({}, Event()), ({"group_id":"*"}, Event()), ({"group_id":"g"}, Event(group="wrong")),
                              ({"group_id":"g"}, Event(text="chat"))]:
            plugin = bridge.QQAuthBridge(None, config)
            plugin._post_json = lambda payload: self.fail("Must not forward")
            self.assertEqual([], [item async for item in plugin.on_group_message(event)])
            self.assertFalse(event.stopped)
    async def test_overload_and_unhandled_silent(self):
        plugin = bridge.QQAuthBridge(None, {"group_id":"g"})
        for _ in range(4): await plugin._slots.acquire()
        event = Event()
        self.assertEqual([], [item async for item in plugin.on_group_message(event)])
        self.assertTrue(event.stopped)
        for _ in range(4): plugin._slots.release()
        plugin._post_json = lambda payload: {"ok":True,"handled":False,"reply":"ignored"}
        self.assertEqual([], [item async for item in plugin.on_group_message(Event())])

    async def test_failure_reply_can_be_customized_or_disabled(self):
        warnings.clear()
        plugin = bridge.QQAuthBridge(None, {"group_id": "g", "error_reply": "稍后再试"})
        def fail(payload):
            raise urllib.error.HTTPError("http://secret.invalid/123456", 401, "Unauthorized", {}, None)
        plugin._post_json = fail
        event = Event(text="验证 123456")
        self.assertEqual(["稍后再试"], [item async for item in plugin.on_group_message(event)])
        self.assertTrue(event.stopped)
        self.assertIn("HTTP 401", warnings[-1])
        self.assertNotIn("123456", warnings[-1])
        plugin.config["error_reply"] = ""
        self.assertEqual([], [item async for item in plugin.on_group_message(Event())])

    def test_safe_diagnostics(self):
        self.assertIn("证书", bridge.failure_reason(urllib.error.URLError(ssl.SSLError("secret"))))
        self.assertIn("超时", bridge.failure_reason(TimeoutError("secret")))
        self.assertIn("端口", bridge.failure_reason(urllib.error.URLError("secret")))
        self.assertNotIn("secret", bridge.failure_reason(ValueError("secret")))
        self.assertIn("连接被拒绝", bridge.failure_reason(urllib.error.URLError(ConnectionRefusedError())))
        self.assertIn("域名解析失败", bridge.failure_reason(urllib.error.URLError(socket.gaierror())))

    def test_http_request_and_response(self):
        received = []
        def respond(handler):
            size = int(handler.headers["Content-Length"])
            received.append((handler.path, handler.headers.get("X-QQAuth-Token"), json.loads(handler.rfile.read(size))))
            data = json.dumps({"ok": True, "handled": True, "reply": "验证成功"}).encode("utf-8")
            handler.send_response(200)
            handler.send_header("Content-Type", "application/json")
            handler.send_header("Content-Length", str(len(data)))
            handler.end_headers()
            handler.wfile.write(data)
        url = self.server(respond)
        plugin = bridge.QQAuthBridge(None, {"qqauth_url": url.removesuffix("/qqauth/group"), "qqauth_token": "test-token"})
        payload = {"group_id": "g", "sender_id": "real", "message": "/验证 123456"}
        self.assertEqual("验证成功", plugin._post_json(payload)["reply"])
        self.assertEqual(("/qqauth/group", "test-token", payload), received[0])

    def test_http_errors_and_redirect_rejection(self):
        seen = []
        def respond(handler):
            seen.append(handler.path)
            handler.send_response(302 if handler.path == "/qqauth/group" else 200)
            handler.send_header("Location", "/redirected")
            handler.end_headers()
        plugin = bridge.QQAuthBridge(None, {"qqauth_url": self.server(respond), "qqauth_token": "test-token"})
        with self.assertRaises(urllib.error.HTTPError) as error:
            plugin._post_json({"group_id": "g", "sender_id": "real", "message": "/验证 123456"})
        self.assertEqual(302, error.exception.code)
        self.assertEqual(["/qqauth/group"], seen)
        error.exception.close()

    def test_invalid_server_response(self):
        def respond(handler):
            handler.send_response(200)
            handler.end_headers()
            handler.wfile.write(b'{"ok":true,"handled":"yes","reply":"done"}')
        plugin = bridge.QQAuthBridge(None, {"qqauth_url": self.server(respond), "qqauth_token": "test-token"})
        with self.assertRaises(ValueError):
            plugin._post_json({"group_id": "g", "sender_id": "real", "message": "/验证 123456"})

    def server(self, respond):
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self): respond(self)
            def log_message(self, *args): pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 3)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}/qqauth/group"

if __name__ == "__main__":
    unittest.main()
