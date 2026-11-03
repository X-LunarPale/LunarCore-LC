# LunarCore QQAuth AstrBot bridge

This AstrBot plugin forwards QQ Official group verification, unbinding and password-reset commands to LunarCore QQAuth. It requires an explicit group openid and a shared token configured on both sides. The token holder can assert group and sender identities: keep the endpoint on a trusted network or behind authenticated HTTPS.

## Install

Copy this entire `astrbot_plugin_qqauth` directory into AstrBot's `data/plugins` directory and restart AstrBot. For a ZIP upload, use forward-slash ZIP entry names and keep `astrbot_plugin_qqauth/` as the top-level directory.

Configure these fields in AstrBot's plugin settings:

| Field | Value |
|---|---|
| `qqauth_url` | Reachable LunarCore HTTP URL ending in `/qqauth/group` |
| `qqauth_token` | The generated token from the LunarCore QQAuth configuration |
| `group_id` | Exact QQ Official `group_openid`; empty and `*` disable forwarding |
| `bind_prefix` | Default `/验证`; match the LunarCore command prefix |
| `unbind_prefix` | Default `/解绑`; match the LunarCore command prefix |
| `password_reset_prefix` | Default `/改密`; match the LunarCore command prefix |
| `timeout_seconds` | Positive timeout up to 60 seconds; default 5 |
| `error_reply` | Optional failure response; empty means silent |

The Java group allowlist must independently match the group ID. QQ Official sender openids are opaque strings, not traditional QQ numbers. Identity comes only from AstrBot event accessors. AstrBot may remove `/` before plugin dispatch; this bridge restores the configured prefix and requires a command boundary.

## Verify

Send ordinary chat: the bridge should stay silent. Send `/验证 000000` in the configured group: LunarCore should return its invalid-code response. Test a real binding, unbinding and password reset using codes issued by the server. Messages in other groups must be ignored.

The bridge sends UTF-8 JSON and `X-QQAuth-Token`. A 401 response means tokens differ; a 400 response means the request envelope is invalid. A 413 response means it exceeds the configured body limit. A 500 response means processing failed. Redirects are rejected, and HTTPS always validates certificates. Four concurrent requests and 64 KiB request/response limits prevent unlimited work allocation. Matching commands stop AstrBot's LLM pipeline even under overload.

## Run local tests

From the module directory, run `python -B -m unittest discover -s bridge -p test_bridge.py -v` for the AstrBot behavior tests without installing AstrBot or contacting a server.

From the LunarCore Gradle root, run `./gradlew :lbr-qqauth:test --tests 'com.lunarboreal.qqauth.bridge.*'`. During development of unrelated module sources, add `-I modules/lbr-qqauth/bridge/bridge-tests.init.gradle` to compile only bridge sources into a separate build directory. The Java tests use loopback HTTP and a local WebSocket peer; no external QQ service is contacted.

## Attribution

Adapted from QQAuth's AstrBot bridge by QQAuth contributors (2026), MIT licensed; see [LICENSE](LICENSE). The LunarCore adaptation adds password-reset schema, command-boundary/slash handling, explicit group checks, bounded forwarding and redacted failure logs.
