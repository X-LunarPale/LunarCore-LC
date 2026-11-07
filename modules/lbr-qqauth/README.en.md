# QQAuth: QQ Verification and Game Accounts

[简体中文](README.md)

Install `lbr-qqauth.jar` in `plugins/LunarCore/modules/`. Requires Java 25, Paper 26.3 and the matching LunarCore version. Accounts and QQ bindings use the LunarCore database.

Do not enable AuthMe or the standalone QQAuth plugin alongside this module. Existing QQ bindings can be imported; passwords are not imported.

## Verification

QQ verification is required by default. Unbound players see a code before joining. Send `/验证 code` in the configured QQ group, then reconnect.

To allow players to join without binding:

```yaml
binding:
  required: false
  reminder:
    enabled: true
    timezone: "" # Blank uses the server time zone.
```

Players still register and sign in normally. After signing in, use `/qqauth bind` to request a code and complete verification in QQ. Binding an online player does not require reconnecting.

Unbound players receive one reminder per calendar day after signing in, including when they remain online across midnight. Reconnecting, reloading or restarting does not repeat that day's reminder. Binding stops reminders. The reminder can be disabled, and its text is customizable under `messages`.

Unverified players cannot change or recover a password, delete their account, or remove a binding themselves. Administrator recovery actions remain available.

## Commands

| Command | Purpose |
|---|---|
| `/register password password` | Register and sign in |
| `/login password` | Sign in |
| `/qqauth bind` | Request a QQ binding code after signing in |
| `/changepassword old-password new-password` | Change password after signing in and verifying QQ |
| `/qqauthreset password new-password` | Request recovery through the bound QQ account |
| `/unbind` | Request unbinding through the bound QQ account |
| `/unregister password` | Delete registration after signing in and verifying QQ |
| `/logout` | Sign out |
| `/qqauth reload` | Reload settings; administrator only |

Unbinding and recovery require the bound QQ account to send `/解绑 code` or `/改密 code` in the same group. Admin commands and web account controls are described in the [Chinese guide](README.md).

## Settings

Edit connection, binding, password and reminder settings in the QQAuth panel. Apply saved settings with **Apply settings**, `qqauth reload`, or a full restart. Reloading disconnects online players so they can authenticate again.

Message edits take effect immediately and support previews. Passwords default to 10–64 characters and at most 72 UTF-8 bytes.

AstrBot uses an independent bridge port, default `12312`. When AstrBot runs in Docker or on another machine, use a reachable game server address. See the [AstrBot setup](bridge/astrbot_plugin_qqauth/README.zh-CN.md) for connection details.

`integration.mode: disabled` stops QQ transport. `binding.required` controls whether unbound players can join.

## Development

Run `:lbr-qqauth:test` for Java tests and `python -B -m unittest discover -s modules/lbr-qqauth/bridge -p test_bridge.py` for bridge tests. Real QQ integration and MySQL need checks in the target environment.

QQAuth is developed by the LunarCore author and collaborators of [qqauth-private](https://github.com/crx97220130101-dotcom/qqauth-private). Distributed with LunarCore under GPL v3. Existing attribution and license notices are retained.
