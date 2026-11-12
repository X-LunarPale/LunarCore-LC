# QQAuth for AstrBot

[简体中文说明](README.zh-CN.md)

This plugin connects QQ Official and OneBot/NapCat group messages in AstrBot to the QQAuth module on your Minecraft server. It handles `/验证`, `/解绑`, and `/改密`. It does not use AstrBot's AI model for verification codes.

## Set up

1. On the Minecraft server, enable QQAuth with `integration.mode: astrbot`. Set `integration.group-id` to the group number for OneBot/NapCat or `group_openid` for QQ Official. The module generates `integration.astrbot.token` on first startup; keep it private. Reload the module after editing its config.
2. Install this public repository from AstrBot's plugin page using its [GitHub URL](https://github.com/X-LunarPale/astrbot_plugin_qqauth), or place this repository at `data/plugins/astrbot_plugin_qqauth/` and restart AstrBot. For a ZIP upload, keep `astrbot_plugin_qqauth/` as the top-level directory.
3. In AstrBot's plugin settings, set `group_id` to the same group ID and `qqauth_token` to the module's token. The token field is hidden in AstrBot settings.
4. If both programs run directly on the same machine, leave `qqauth_url` at `http://127.0.0.1:12312/qqauth/group`. If AstrBot runs in Docker or on another host, `127.0.0.1` points to AstrBot itself: use the game server's reachable address and configure QQAuth's `integration.astrbot.bind-address` accordingly. Keep the endpoint on a trusted network or use HTTPS.
5. Send `/验证 000000` in the configured QQ group. The server should respond that the code is invalid. Then test a real code displayed when joining Minecraft. Other groups and ordinary chat are ignored.

The bridge port defaults to `12312` and is separate from the Web panel, game and RCON ports. Forward or open that port when connecting from another host. A URL pointing to the Web or RCON port cannot reach this bridge.

Keep QQAuth in `astrbot` mode when AstrBot uses OneBot/NapCat. QQAuth's `onebot` mode connects directly to OneBot without this plugin. QQ Official group and user openids are different from group and QQ numbers; changing adapters does not convert existing bindings.

## Settings

| Setting | Meaning |
|---|---|
| `qqauth_url` | QQAuth HTTP address; default port is `12312`. A missing path becomes `/qqauth/group` |
| `qqauth_token` | Token from `integration.astrbot.token` on the Minecraft server |
| `group_id` | Exact group number or QQ Official `group_openid` also set on the server |
| `bind_prefix`, `unbind_prefix`, `password_reset_prefix` | Optional group command names; aliases are translated to QQAuth's `/验证`, `/解绑`, `/改密` |
| `timeout_seconds` | Request timeout; default 5 seconds |
| `tls_ca_file` | Optional CA file for a trusted HTTPS certificate |
| `error_reply` | Group reply when the server cannot be reached; set to empty to stay silent |

AstrBot's logs give short connection hints without printing tokens or verification codes: HTTP 401 means mismatched token; 404 usually means a wrong path or port; refused connections usually mean QQAuth is not running or the host/port is wrong; timeouts suggest port forwarding, firewall or network issues. The game server logs its actual listening address. On a request failure, players only receive the configured `error_reply`.

## Test

From the repository root, run:

```sh
python -B -m unittest discover -s tests -p test_bridge.py -v
```

These tests use a local HTTP server, not a real QQ group. Real QQ messages and the Minecraft join flow still need a live check.

Copyright (c) 2026 QQAuth contributors. See [LICENSE](LICENSE).
