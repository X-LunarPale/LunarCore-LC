# QQAuth 的 AstrBot 插件

这个插件把 AstrBot 的 QQ 群消息转给 Minecraft 服务器上的 QQAuth，支持 QQ 官方机器人和 OneBot/NapCat。玩家可以在群里发送 `/验证`、`/解绑`、`/改密`。验证码不会交给 AI 聊天处理。

## 安装

1. 在游戏服的 QQAuth 配置中，把 `integration.mode` 设为 `astrbot`。`integration.group-id` 填验证群 ID：OneBot/NapCat 填群号，QQ 官方机器人填 `group_openid`。首次启动会生成 `integration.astrbot.token`；不要把它发到群里。改完服务器配置后重载 QQAuth 模块。
2. 在 AstrBot 插件页面填写[本仓库地址](https://github.com/X-LunarPale/astrbot_plugin_qqauth)安装；也可以把仓库放进 `data/plugins/astrbot_plugin_qqauth/` 后重启 AstrBot。若上传 ZIP，压缩包内第一层应是 `astrbot_plugin_qqauth/`。
3. 打开 AstrBot 的插件设置，把 `group_id` 填成相同的群 ID，把 `qqauth_token` 填成游戏服生成的密钥。密钥输入框会隐藏内容。
4. AstrBot 和游戏服都直接运行在同一台机器时，默认地址 `http://127.0.0.1:12312/qqauth/group` 可用。**如果 AstrBot 在 Docker 或另一台机器，不能照用 127.0.0.1**；请填游戏服对 AstrBot 可访问的地址，同时调整游戏服的 `integration.astrbot.bind-address`，只对可信网络开放。
5. 在指定群里发送 `/验证 000000`，应收到“验证码无效”之类的回复。然后用玩家进服时获取的真实验证码测试。其他群的消息不会转发。

QQAuth 的桥接默认端口是 `12312`，与 Web 面板、游戏、RCON 端口互相独立。跨机器还需映射或开放这个端口。若地址填的是 Web 或 RCON 端口，修改 URL 并不能把它变为 QQAuth 接口。

AstrBot 使用 OneBot/NapCat 时，游戏服仍选择 `integration.mode: astrbot`。QQAuth 的 `onebot` 模式是由游戏服直连 OneBot，不经过本插件。QQ 官方机器人的 `group_openid` 和用户 openid 与群号、QQ 号不是同一个身份；更换接入方式不会自动转换旧绑定。

## 配置项

| 项目 | 填什么 |
|---|---|
| `qqauth_url` | QQAuth HTTP 地址，默认端口 `12312`；省略路径时自动补齐 `/qqauth/group` |
| `qqauth_token` | 游戏服 `integration.astrbot.token` 的值 |
| `group_id` | 与游戏服 `integration.group-id` 相同的群号或 QQ 官方群 ID |
| `bind_prefix`、`unbind_prefix`、`password_reset_prefix` | 群里的命令名称；改成别名后插件会转换成服务器识别的三个命令 |
| `timeout_seconds` | 连接等待时间，默认 5 秒 |
| `tls_ca_file` | 使用自建 HTTPS 证书时可填受信任证书文件 |
| `error_reply` | 连接失败时给群里的提示；留空则不回复 |

连接失败时看 AstrBot 日志：`HTTP 401` 表示密钥不一致，`HTTP 404` 通常是路径或端口错误，“连接被拒绝”通常是 QQAuth 未启动或地址、端口不对，“请求超时”通常是端口映射、防火墙或网络不通。游戏服启动日志会显示实际监听地址。日志不会打印密钥和验证码。

## 本地测试

在仓库根目录运行：

```sh
python -B -m unittest discover -s tests -p test_bridge.py -v
```

这些测试只使用本机临时 HTTP 服务；实际 QQ 群和玩家进服流程仍需接入后验证。

由 QQAuth 协作者共同开发，许可见 [LICENSE](LICENSE)。
