"""Local tests; AstrBot is stubbed and no network is used."""
import asyncio
import importlib.util
import pathlib
import sys
import types
import unittest

api = types.ModuleType("astrbot.api")
api.AstrBotConfig = dict
api.logger = types.SimpleNamespace(warning=lambda *args: None)
events = types.ModuleType("astrbot.api.event")
events.AstrMessageEvent = object
class Filters:
    PlatformAdapterType = types.SimpleNamespace(QQOFFICIAL=1, QQOFFICIAL_WEBHOOK=2)
    EventMessageType = types.SimpleNamespace(GROUP_MESSAGE=1)
    @staticmethod
    def platform_adapter_type(*args): return lambda fn: fn
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
spec = importlib.util.spec_from_file_location("qqauth_bridge", pathlib.Path(__file__).parent / "astrbot_plugin_qqauth" / "main.py")
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
        self.assertEqual("/改密 123456", bridge.normalize_command("改密 123456", ["/改密"]))
        self.assertEqual("/验证 123456", bridge.normalize_command("/验证 123456", ["/验证"]))
        self.assertIsNone(bridge.normalize_command("验证者 123456", ["/验证"]))
        self.assertIsNone(bridge.normalize_command("chat", [""]))
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

if __name__ == "__main__":
    unittest.main()
