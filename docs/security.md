# 生产环境安全清单

## OneBot

- 生产环境设置非空且不可猜测的 Access Token；
- 限制 `allowed-group-ids`，避免使用 `allow-all-groups: true`；
- 同机使用 `ws://127.0.0.1`；
- 跨主机使用 `wss://`；
- 只有在可信加密隧道中才考虑 `allow-insecure-remote-websocket`；
- 通过防火墙限制 OneBot 端口来源；
- 不要在日志、截图或 Issue 中公开 Token。

## 数据库

- 为 ShitBot 创建独立数据库账号；
- 只授予目标数据库所需权限；
- 远程 MySQL 使用 `sslMode=VERIFY_IDENTITY`；
- 保持 `allowPublicKeyRetrieval=false`；
- 不要在多个进程间共享 SQLite 文件；
- 升级、迁移和更换平台前备份；
- 不要公开数据库 URL、账号或密码。

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
- 不要无上限提高数据库、图片渲染或命令队列；
- 背包资源包和客户端 JAR 只使用可信来源；
- 头像与外部图片服务应使用 HTTPS。
