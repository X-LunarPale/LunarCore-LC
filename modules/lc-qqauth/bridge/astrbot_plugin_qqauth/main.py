"""QQAuth AstrBot bridge. Copyright (c) 2026 QQAuth contributors.
SPDX-License-Identifier: MIT. See LICENSE.
"""
import asyncio
import json
import socket
import ssl
import urllib.error
import urllib.request
from urllib.parse import urlsplit, urlunsplit

from astrbot.api import AstrBotConfig, logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star


COMMANDS = (("bind_prefix", "验证"), ("unbind_prefix", "解绑"),
            ("password_reset_prefix", "改密"))
DEFAULT_URL = "http://127.0.0.1:12312/qqauth/group"
DEFAULT_ERROR_REPLY = "QQ 验证服务暂不可用，请稍后重试。"


def bridge_url(value):
    """Accept a server base address as well as the full bridge endpoint."""
    parsed = urlsplit(str(value).strip())
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Invalid bridge URL")
    path = parsed.path.rstrip("/")
    return urlunsplit(parsed._replace(path=path or "/qqauth/group"))


def normalize_command(message, prefixes):
    """Accept configured aliases, but forward the three commands QQAuth understands."""
    text = (message or "").strip().removeprefix("/")
    for configured, command in prefixes:
        bare = str(configured).strip().removeprefix("/")
        if bare and (text == bare or (text.startswith(bare) and text[len(bare):len(bare)+1].isspace())):
            return "/" + command + text[len(bare):]
    return None


def failure_reason(error):
    """Return a useful diagnostic without logging the URL, token or verification code."""
    if isinstance(error, urllib.error.HTTPError):
        return {
            400: "HTTP 400：请求格式不匹配，请核对 QQAuth 模块版本",
            401: "HTTP 401：桥接密钥不一致",
            403: "HTTP 403：接口访问被拒绝，请检查反向代理配置",
            404: "HTTP 404：接口地址应以 /qqauth/group 结尾",
            413: "HTTP 413：消息过长",
        }.get(error.code, f"HTTP {error.code}：QQAuth 服务返回错误")
    if isinstance(error, (TimeoutError, asyncio.TimeoutError)):
        return "请求超时，请检查 QQAuth 服务是否运行"
    if isinstance(error, ssl.SSLError):
        return "HTTPS 证书校验失败，请检查证书或 tls_ca_file"
    if isinstance(error, urllib.error.URLError):
        if isinstance(error.reason, ssl.SSLError):
            return "HTTPS 证书校验失败，请检查证书或 tls_ca_file"
        if isinstance(error.reason, TimeoutError):
            return "请求超时，请检查 QQAuth 监听地址、端口映射和防火墙"
        if isinstance(error.reason, ConnectionRefusedError):
            return "连接被拒绝：请检查 QQAuth 是否启用及桥接端口；Docker/异机不能使用 127.0.0.1"
        if isinstance(error.reason, socket.gaierror):
            return "域名解析失败，请检查 QQAuth 地址"
        return "无法连接 QQAuth，请检查桥接地址和端口；该接口不使用 Web 或 RCON 端口"
    if isinstance(error, ValueError):
        return "桥接配置或服务响应无效，请检查 QQAuth 地址、密钥和日志"
    return "请求失败，请检查 QQAuth 服务日志"


class QQAuthBridge(Star):
    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.config = config
        self._slots = asyncio.Semaphore(4)
        self._closed = False

    @filter.platform_adapter_type(filter.PlatformAdapterType.AIOCQHTTP |
                                  filter.PlatformAdapterType.QQOFFICIAL |
                                  filter.PlatformAdapterType.QQOFFICIAL_WEBHOOK)
    @filter.event_message_type(filter.EventMessageType.GROUP_MESSAGE)
    async def on_group_message(self, event: AstrMessageEvent):
        if self._closed:
            return
        group_id = str(event.get_group_id() or "").strip()
        allowed = str(self.config.get("group_id", "")).strip()
        if not allowed or allowed == "*" or allowed != group_id:
            return
        message = normalize_command(event.message_str, ((self.config.get(key, "/" + command), command)
            for key, command in COMMANDS))
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
        except Exception as error:
            logger.warning("QQAuth 桥接：" + failure_reason(error))
            if isinstance(error, urllib.error.HTTPError):
                error.close()
            reply = str(self.config.get("error_reply", DEFAULT_ERROR_REPLY))
            if reply and not self._closed:
                yield event.plain_result(reply)
            return
        if not self._closed and response.get("handled") is True:
            reply = response.get("reply", "")
            if isinstance(reply, str) and reply:
                yield event.plain_result(reply)

    def _post_json(self, payload):
        url = bridge_url(self.config.get("qqauth_url", DEFAULT_URL))
        parsed_url = urlsplit(url)
        token = str(self.config.get("qqauth_token", "")).strip()
        timeout = float(self.config.get("timeout_seconds", 5))
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
        if (not isinstance(result, dict) or result.get("ok") is not True
                or not isinstance(result.get("handled"), bool)
                or not isinstance(result.get("reply"), str)):
            raise ValueError("Invalid bridge response")
        return result

    async def terminate(self):
        self._closed = True
