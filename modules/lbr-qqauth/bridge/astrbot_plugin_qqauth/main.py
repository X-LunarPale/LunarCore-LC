"""QQAuth AstrBot bridge. Copyright (c) 2026 QQAuth contributors.
SPDX-License-Identifier: MIT. Adapted for LunarCore; see LICENSE.
"""
import asyncio
import json
import ssl
import urllib.request
from urllib.parse import urlsplit

from astrbot.api import AstrBotConfig, logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star


def normalize_command(message, prefixes):
    """AstrBot may strip its slash wake prefix; restore the configured prefix."""
    text = message.strip().lstrip("/")
    for configured in prefixes:
        prefix = str(configured).strip()
        bare = prefix.lstrip("/")
        if bare and (text == bare or (text.startswith(bare) and text[len(bare):len(bare)+1].isspace())):
            return prefix + text[len(bare):]
    return None


class QQAuthBridge(Star):
    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.config = config
        self._slots = asyncio.Semaphore(4)
        self._closed = False

    @filter.platform_adapter_type(filter.PlatformAdapterType.QQOFFICIAL | filter.PlatformAdapterType.QQOFFICIAL_WEBHOOK)
    @filter.event_message_type(filter.EventMessageType.GROUP_MESSAGE)
    async def on_group_message(self, event: AstrMessageEvent):
        if self._closed:
            return
        group_id = str(event.get_group_id() or "").strip()
        allowed = str(self.config.get("group_id", "")).strip()
        if not allowed or allowed == "*" or allowed != group_id:
            return
        message = normalize_command(event.message_str or "", [self.config.get("bind_prefix", "/验证"),
            self.config.get("unbind_prefix", "/解绑"), self.config.get("password_reset_prefix", "/改密")])
        if message is None:
            return
        # Codes must not enter the normal LLM pipeline, including overload cases.
        event.stop_event()
        sender = str(event.get_sender_id() or "").strip()
        if not sender or self._slots.locked():
            return
        payload = {"group_id": group_id, "sender_id": sender, "message": message}
        try:
            async with self._slots:
                response = await asyncio.to_thread(self._post_json, payload)
        except Exception:
            # URLs, HTTP errors and payloads may contain credentials or codes.
            logger.warning("QQAuth bridge request failed")
            reply = str(self.config.get("error_reply", ""))
            if reply and not self._closed:
                yield event.plain_result(reply)
            return
        if not self._closed and response.get("handled") is True:
            reply = response.get("reply", "")
            if isinstance(reply, str) and reply:
                yield event.plain_result(reply)

    def _post_json(self, payload):
        url = str(self.config.get("qqauth_url", "http://127.0.0.1:8080/qqauth/group")).strip()
        parsed_url = urlsplit(url)
        token = str(self.config.get("qqauth_token", "")).strip()
        timeout = float(self.config.get("timeout_seconds", 5))
        if parsed_url.scheme not in ("http", "https") or not parsed_url.hostname or parsed_url.username or parsed_url.password:
            raise ValueError("Invalid bridge URL")
        if not token or not 0 < timeout <= 60:
            raise ValueError("Invalid bridge settings")
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        if len(data) > 65536:
            raise ValueError("Message too large")
        request = urllib.request.Request(url, data=data, method="POST", headers={
            "Accept": "application/json", "Content-Type": "application/json; charset=utf-8", "X-QQAuth-Token": token})
        # Never forward the bridge token across a redirect. TLS verification stays enabled.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        handlers = [NoRedirect]
        ca_file = str(self.config.get("tls_ca_file", "")).strip()
        if ca_file and parsed_url.scheme == "https":
            context = ssl.create_default_context(cafile=ca_file)
            handlers.append(urllib.request.HTTPSHandler(context=context))
        with urllib.request.build_opener(*handlers).open(request, timeout=timeout) as response:
            body = response.read(65537)
        if len(body) > 65536:
            raise ValueError("Response too large")
        result = json.loads(body.decode("utf-8"))
        if not isinstance(result, dict) or result.get("ok") is not True:
            raise ValueError("Unsuccessful response")
        return result

    async def terminate(self):
        self._closed = True
