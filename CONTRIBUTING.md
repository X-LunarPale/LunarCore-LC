# 参与开发与模块投稿

[English](CONTRIBUTING.en.md) | [模块市场](module-market/README.md)

任何人都可以下载、修改代码、报告问题和提交模块。浏览市场、下载附件与面板安装均不需要 GitHub 令牌。提交内容需要一个免费的 GitHub 账号。

## 提交模块

1. 将模块源码放到公开仓库，并在该仓库的 Release 上传 JAR。
2. 在本仓库点击 **Fork**，在 `module-market/community/` 新建 `模块ID.json`。
3. 复制 [示例](module-market/submission.example.json)，填写名称、用途、版本、下载链接、源码地址、许可和文件校验值。
4. 点击 **Contribute → Open pull request** 提交。附上目录校验结果，维护者合入后即可在目录看到条目。

不会编辑 JSON 时，可在 **Issues → New issue → 模块投稿** 填表提交公开下载地址。两种方式都对所有人开放。

社区作者在自己的 Release 发布和更新文件，不需要本仓库的访问令牌。目录变更通过 PR 合入；投稿不会直接替换官方附件。社区模块目前从 GitHub 市场下载后手动安装，面板内一键安装提供与核心一起发布的 19 个模块。

## 填写说明

| 字段 | 填写内容 |
|---|---|
| `id` | 小写英文、数字、短横线或下划线，与模块 `module.yml` 一致 |
| `name`、`nameEn` | 中英文名称 |
| `description`、`descriptionEn` | 中英文用途说明 |
| `version` | 模块自己的版本 |
| `coreVersions` | 已测试的核心版本，如 `["0.0.6-preview"]` |
| `author` | 作者名称 |
| `source` | 公开源码仓库的 HTTPS 地址 |
| `download` | 公开 JAR 的 HTTPS 下载地址 |
| `license` | 模块所用开源许可 |
| `depends` | 必需的 LC 模块 ID，没有则填 `[]` |

提交前运行 `python tools/validate-module-market.py`，检查文件格式、重复 ID 和依赖。

## 修改项目

按 [开发与构建](DEVELOPMENT.md)运行相关检查，说明改动用途与测试结果，再提交 PR。保持中英文文档对应，保留 GPL v3 和第三方许可。

不要在投稿中放入运行服配置、玩家数据或私有密钥。
