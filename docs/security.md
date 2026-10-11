# 生产环境安全清单

**简体中文** | [English](en/security.md)

## OneBot

- 生产环境设置非空且不可猜测的 Access Token；
- 限制 `allowed-group-ids`，避免使用 `allow-all-groups: true`；
- 同机使用 `ws://127.0.0.1`；
- 跨主机使用 `wss://`；
- 只有在可信加密隧道中才考虑 `allow-insecure-remote-websocket`；
- 通过防火墙限制 OneBot 端口来源；
- 不要在日志、截图或 Issue 中公开 Token。

OneBot 在 WebSocket 解码阶段限制入站消息为 2 MiB，避免完整分配超大消息后才拒绝。明文连接的回环豁免仅接受 `localhost`、明确的 IPv6 回环地址或规范的 `127.x.x.x` IPv4 地址，不接受以 `127.` 开头的域名。

## 数据库

- 为 ShitBot 创建独立数据库账号；
- 只授予目标数据库所需权限；
- 远程 MySQL 使用 `sslMode=VERIFY_IDENTITY`；
- 保持 `allowPublicKeyRetrieval=false`；
- 不要在多个进程间共享 SQLite 文件；
- 升级、迁移和更换平台前备份；
- 不要公开数据库 URL、账号或密码。

远程 MySQL 的 TLS 检查按独立参数解析，`sslMode` 优先于旧版 SSL 参数。重复或非规范大小写的安全参数不能满足该检查；只使用旧版参数时，需显式设置 `useSSL=true`，并设置 `requireSSL=true` 或 `verifyServerCertificate=true`。原有的 `allow-insecure-remote-mysql` 显式开关仍可使用。

## 代理—后端通道

- 每个后端使用不同的至少 16 位随机 Token；
- 同机 listener 绑定 `127.0.0.1`；
- 跨机器启用 TLS；
- 后端 `allowed-proxy-addresses` 只填写代理实际 IP；
- 防火墙只允许代理访问监听端口；
- 不要把 listener 直接暴露到公网；
- TLS 环境仍保留 HMAC Token；
- 需要更强身份校验时启用双向 TLS。

HMAC 提供请求认证和完整性，不提供内容加密。跨机器通信必须由 TLS 或可信加密隧道保护。

## QQ 快捷命令

- 只配置确实需要的控制台命令；
- 为敏感快捷命令填写 `permission`；
- 保持 `allow-unbound: false`；
- 限制允许群和命令别名；
- 不要把任意群输入直接拼接进开放式控制台命令；
- 定期检查 LuckPerms、Vault 和 OP 权限；
- 观察命令冷却、超时和队列是否符合实际负载。

`permission` 为空只表示不检查游戏权限。将 `allow-unbound` 设为 `true` 才会允许未绑定群成员调用，因此修改此项前必须重新评估命令风险。

## 高级图片模板

- 不需要高级模板时保持 `custom-image-templates.enabled: false`，此时不会下载或加载渲染组件；
- 只使用官方 Release 中同版本的 `ShitBotRenderer`、`.sha256` 和 `.sig`，不要绕过组件签名；
- 编辑器保持回环监听，通过 HTTPS 反向代理远程使用时不要直接公开内部端口，并保留原始 `Host`；
- `/shitbot editor` 登录地址是短时一次性凭据，不要发送到群聊、日志或 Issue；
- 远程图片默认开启以支持玩家头像，仍受 HTTPS、地址和资源上限限制；不需要网络头像或素材时，可关闭 `custom-image-templates.remote-images.enabled`；
- 不要手工修改 `versions/` 与 `published.yml`，生产渲染只应使用发布快照；
- 不要无上限提高画布、像素、图层、循环、资源、超时、线程和队列限制；
- 自定义数据提供器必须在访问平台 API 时自行切换到正确线程，并只返回渲染所需数据。

当前模板引擎不执行上传的 HTML、JavaScript 或任意服务器命令，也不包含浏览器内核。`commands.yml` 的图片模板入口与控制台快捷命令是两套独立机制。

远程模板图片仅通过 HTTPS 443 直接连接经检查的公网地址，每次重定向重新检查并固定连接地址，同时保留原始域名的 TLS 校验。此路径不使用 JVM 的 HTTP/SOCKS 代理，以免代理重新解析目标而绕过地址限制。下载使用 2 个工作线程和 16 个排队位置；缓存最多保留 128 个条目及合计 16 Mi 像素，网络读取受渲染超时约束。Base64 大小与图片尺寸均在解码前检查。

模板 YAML 的归一化拒绝循环引用，并限制为 64 层深度、65,536 个展开节点；别名展开也计入限制。素材上传使用随机、排他创建的临时文件，拒绝符号链接目标；源码读取同样拒绝符号链接文件。

## 账号绑定

- 保持验证码有效期和尝试次数限制；
- 不要把 `code-alphabet` 改成过小字符集；
- 按服务器业务设置 `maximum-ids-per-qq`；
- 开启 `group-leave-unbind` 前确认退群即解绑符合业务规则；
- 数据库异常时不要绕过绑定检查；
- 处理迁移文件后及时移走 EasyBot 数据库副本。

## 更新

- 只从官方 GitHub Release 获取 JAR；
- 保留 `.sha256` 与 `.sig` 校验链；
- 更新失败时检查原因，不绕过签名；
- 群组服同时更新代理和后端；
- 替换 JAR 后正常重启，不使用插件管理器热加载；
- 发布私钥只保存在仓库 Secret。

## 日志与备份

- 对外发送日志前删除 Token、密码、数据库地址和玩家隐私；
- QQ 快捷命令的日志脱敏只是额外保护，不应替代最小权限；
- 数据库备份应加密并限制访问；
- 配置备份中同样包含敏感信息；
- 不要把生产配置、数据库或签名私钥提交到 Git；
- 定期确认备份可以恢复。

## 网络与资源

- 限制数据库、OneBot、后端 listener 和管理面板端口的来源；
- 为远程服务配置连接与读取超时；
- 不要无上限提高数据库、图片渲染、数据提供器或命令队列；
- 背包资源包和客户端 JAR 只使用可信来源；
- 头像与外部图片服务应使用 HTTPS。
