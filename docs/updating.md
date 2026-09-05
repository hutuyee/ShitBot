# 升级与自动更新

## 升级前

1. 阅读目标版本的 [Release Notes](https://github.com/hutuyee/ShitBot/releases)；
2. 备份数据库；
3. 备份 `config.yml`、`commands.yml`、`lang/`、`templates/` 和自定义图片资源；
4. 群组服确认代理与后端是否需要同时升级；
5. 不要在服务端运行期间直接复制 SQLite 数据库。

代理—后端协议发生变化时，必须同时升级代理和全部 Spigot 后端。混用不同协议版本可能导致命令、TPS 查询或联动更新失败。

## 手动升级

1. 停止服务器或代理；
2. 下载当前平台的新 JAR；
3. 移走旧 JAR，只保留一个 ShitBot 平台 JAR；
4. 放入新 JAR；
5. 启动并观察配置迁移与数据库迁移日志；
6. 对照新版本默认配置补充新增配置项；
7. 执行 `/shitbot status`；
8. 验证 OneBot、绑定、转发和快捷命令。

不要通过插件管理器热卸载旧 JAR 再加载新 JAR。

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
7. 将当前 JAR 备份为同目录的 `.bak`；
8. 替换 JAR，并提示手动重启。

更新命令不会热重载插件。完成后必须正常重启服务器或代理。

任意校验失败时，更新器会保留当前 JAR。不要通过手动删除 `.sha256`、`.sig` 或修改 Release 文件来绕过验证。

## 群组服联动更新

在 BungeeCord 执行 `/shitbot update` 时，代理会将同一 Release 中的 Spigot JAR和校验元数据发送到 `backend-transport.endpoints` 中已配置的后端。

- 每个后端独立验证并替换自己的 JAR；
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
- 同时存在对应的 `.jar.sha256` 和 `.jar.sig`；
- JAR、校验文件和签名来自同一个 Release；
- 插件目录可写；
- 当前 JAR 路径可识别且没有被其他程序锁定；
- 群组服的代理—后端通道处于正常状态。

如果自动更新失败，可以保留日志后改用手动升级，但仍应从官方 Release 下载。

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
