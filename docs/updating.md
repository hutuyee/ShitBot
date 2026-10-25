# 升级与自动更新

**简体中文** | [English](en/updating.md)

## 升级前

1. 阅读目标版本的 [Release Notes](https://github.com/hutuyee/ShitBot/releases)；
2. 备份数据库；
3. 备份 `config.yml`、`commands.yml`、`lang/`、`templates/`、`image-templates/` 和自定义图片资源；
4. 群组服确认代理与后端是否需要同时升级；
5. 不要在服务端运行期间直接复制 SQLite 数据库。

代理—后端协议发生变化时，必须同时升级代理和全部 Spigot 后端。混用不同协议版本可能导致命令、TPS 查询或联动更新失败。

启用高级图片模板的实例还要求官方 Release 存在同版本 `ShitBotRenderer`。平台插件更新后，下一次启动会按新版本检查组件缓存；只有高级模板总开关开启时才会下载新组件。

## 手动升级

1. 停止服务器或代理；
2. 下载当前平台的新 JAR；
3. 移走旧 JAR，只保留一个 ShitBot 平台 JAR；
4. 放入新 JAR；
5. 启动并观察配置迁移与数据库迁移日志；
6. 对照 `config-backups/` 中升级前的配置，查看自动写入配置、语言文件和图片主题的新增字段，并按需修改；
7. 执行 `/shitbot status`；
8. 验证 OneBot、绑定、转发和快捷命令。

不要通过插件管理器热卸载旧 JAR 再加载新 JAR。

检测到插件版本变化后，会在读取和修改配置之前备份已有的 `config.yml`、`commands.yml`、`lang/` 和 `templates/` 中的 YAML 文件。备份位于插件数据目录的 `config-backups/`，保留原始内容和相对路径；首次使用备份功能的已有安装也会执行一次。每次自动覆盖配置文件前还会保存该文件的原件。备份失败会停止对应写入。位置、保留和恢复规则见[自动配置备份](configuration.md#自动配置备份)。

启动和重载会自动补齐配置、语言文件和图片主题中的缺失键或 `null` 值，并保存到磁盘；已有值会保留，具体规则见[配置说明](configuration.md)。从 `config-version: 1` 升级时，首次加载会先把旧 `config.yml` 的回复、通知、内置别名/用法和图片标题自动导入 `lang/zh_CN.yml`，再补齐主配置。确认导入结果后，再自行清理旧配置中的废弃文本项。迁移完成状态记录在 `zh_CN.yml` 的 `_migration.legacy-config-v1`，因此后续 reload 不会重复覆盖。

## `/shitbot update`

有 `shitbot.admin` 权限的管理员可以执行：

```text
/shitbot update
```

更新器会：

1. 查询最新 GitHub Release；
2. 选择当前平台的 JAR；
3. 下载 JAR、`.sha256` 和 `.sig`；
4. 校验下载地址、文件大小和 SHA-256；
5. 使用内置公钥验证独立 RSA 签名；
6. 检查 JAR 平台描述、主类和内嵌版本；
7. 将当前配置备份到数据目录的 `config-backups/`，并在日志中输出路径；
8. 将当前 JAR 备份为同目录的 `.bak`；
9. 按当前平台的方式替换或暂存 JAR，并提示正常重启。

Spigot、Paper、Folia 使用服务端配置的更新目录，默认是 `plugins/update/`。新 JAR 完成全部校验和备份后才放入该目录，服务端在下次启动、加载插件之前替换当前 JAR。这一流程用于避开 Windows 对已加载插件 JAR 的文件占用。

待更新文件必须沿用当前插件的文件名。例如当前文件是 `ShitBotSpigot-1.1.5.jar`，暂存的 1.2.0 也保存为 `plugins/update/ShitBotSpigot-1.1.5.jar`；实际版本取自 JAR 内的插件描述。不要在重启前改名或移走当前 JAR，否则服务端可能无法匹配待更新文件。

命令会显示“已暂存”和待更新文件路径；同一进程中再次执行时，如果文件仍在，会提示“已暂存”，文件被移走后则重新准备更新。`bukkit.yml` 中的 `settings.update-folder` 必须启用并指向独立目录；为空、指向插件目录或待更新路径指回当前 JAR 时，更新会失败并保留当前 JAR。

BungeeCord、Velocity、Nukkit-MOT 仍使用校验后直接替换磁盘 JAR、重启加载的流程。如果旧版 Spigot 更新器已因 Windows 文件占用而失败，需要先停服并手动安装包含暂存修复的版本，之后才能使用新流程。

更新命令不会热重载插件。完成后必须正常重启服务器或代理。

任意校验或配置备份失败时，更新器会保留当前 JAR。不要通过手动删除 `.sha256`、`.sig` 或修改 Release 文件来绕过验证。

## 群组服联动更新

在 BungeeCord 执行 `/shitbot update` 时，代理会将同一 Release 中的 Spigot JAR和校验元数据发送到 `backend-transport.endpoints` 中已配置的后端。

- 每个 Spigot 后端独立验证新 JAR、备份配置与当前 JAR，再暂存到自己的更新目录，重启时替换；
- 代理会分别返回各后端结果；
- 未配置 endpoint 的子服不会被扫描或更新；
- 更新完成后仍需手动重启代理和各后端；
- 后端模式的 Spigot 不会独立检查 GitHub，由代理负责通知和分发。

执行联动更新前，确认代理—后端通道正常，并为各端数据库与配置创建备份。

## 自动检查

插件启动后会在后台检查 GitHub Release。检查不会占用服务端主线程；最新结果发生变化时会写入插件数据目录下的 `update-cache.json`。

有 `shitbot.admin` 权限的管理员上线时，会收到可用新版本和 Release 链接提示。自动检查只负责通知，不会未经命令自动替换插件。

## 更新失败排查

检查：

- 当前实例能够访问 `api.github.com` 和 GitHub Release 下载域名；
- Release 包含当前平台 JAR；
- 高级模板已启用时，Release 同时包含同版本 `ShitBotRenderer`；
- 同时存在对应的 `.jar.sha256` 和 `.jar.sig`；
- JAR、校验文件和签名来自同一个 Release；
- 插件目录可写；Spigot 的服务端更新目录也必须可写且已启用；
- 当前 JAR 路径可识别并可读取；使用直接替换流程的平台还需要允许覆盖该文件；
- 群组服的代理—后端通道处于正常状态。

如果自动更新失败，可以保留日志后改用手动升级，但仍应从官方 Release 下载。

后台安装或暂存失败时，控制台会记录完整异常堆栈；Spigot 命令和后端联动回复也会包含异常类型，便于区分文件占用、权限、目录配置和网络错误。

## 发布维护者：签名密钥

普通服主不需要配置任何更新公钥或私钥。公钥内置在 ShitBotCore 中，私钥只应保存在 GitHub Actions 的 `SHITBOT_UPDATE_PRIVATE_KEY` Secret。

首次生成或轮换密钥：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out update-private-key.pem
openssl pkey -in update-private-key.pem -pubout -out update-public-key.pem
```

轮换时需要：

1. 更新 ShitBotCore 内置公钥；
2. 更新仓库 Secret 中的私钥；
3. 通过可信的手动升级方式把包含新公钥的版本部署到现有实例；
4. 再使用新私钥签署后续 Release。

私钥不能提交到仓库、打包进 JAR 或分发给服主。
