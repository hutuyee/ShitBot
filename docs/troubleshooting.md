# 常见问题

排错时先执行：

```text
/shitbot status
```

记录运行平台、服务端版本、Java 版本，以及出现问题前后的完整控制台日志。

## OneBot 一直显示 disconnected

检查：

1. OneBot v11 正向 WebSocket 服务已经开启；
2. `onebot.websocket-url` 的协议、地址和端口正确；
3. `access-token` 与 OneBot 配置一致；
4. `127.0.0.1` 指向的是运行 ShitBot 的机器，不是另一台 OneBot 主机；
5. 跨主机地址使用 `wss://`，远程 `ws://` 默认会被拒绝；
6. Java 信任 `wss://` 证书；
7. 防火墙允许 Minecraft 实例连接 OneBot；
8. OneBot 实现确实兼容 OneBot v11 正向 WebSocket。

## QQ 指令没有响应

检查：

- 群号已加入 `onebot.allowed-group-ids`，或明确启用了 `allow-all-groups`；
- OneBot 上报的是群消息；
- 对应 `onebot.commands.<功能>.enabled` 已开启；
- 输入文本在 `aliases` 中；
- 指令不处于冷却时间；
- 机器人拥有发送群消息和图片的权限；
- `/shitbot status` 显示数据库与 OneBot 正常。

## `#qq` 或 `#mc` 不转发

两个方向有独立开关。确认：

```yaml
forwarding:
  game-to-group:
    enabled: true
  group-to-game:
    enabled: true
```

如果 `require-prefix: true`，输入必须包含完整前缀，包括配置中末尾的空格。游戏到 QQ 的目标群来自 `allowed-group-ids`。

## 玩家绑定后仍然被拦截

检查：

- QQ 指令中的游戏 ID 大小写与实际名称完全一致；
- 绑定指令使用的是玩家本次登录生成的最新验证码；
- 验证码仍在有效期内；
- QQ 未超过 `maximum-ids-per-qq`；
- 群组服的代理和后端连接同一个 MySQL；
- 数据库中存在该玩家绑定；
- 数据库字符集、排序规则和表结构没有被手动修改。

## 数据库连接失败

SQLite：

- 插件数据目录可写；
- 没有其他进程同时打开共享文件；
- 磁盘空间充足。

MySQL：

- 地址、端口、数据库、账号和密码正确；
- MySQL 用户拥有目标数据库权限；
- 远程连接使用 `sslMode=VERIFY_IDENTITY`；
- Java 信任数据库证书；
- 服务器能访问 `repo.maven.apache.org`，或已有兼容 JDBC 驱动；
- 防火墙和 MySQL 监听地址允许连接。

## 代理提示没有可用子服

检查：

1. 后端 Spigot 已设为 `deployment.role: "backend"`；
2. 后端 listener 已启用；
3. endpoint 的名称与后端 `server-name` 一致；
4. 地址、端口和 Token 两端一致；
5. Token 至少 16 位；
6. 后端 `allowed-proxy-addresses` 包含代理实际 IP；
7. 跨机器连接已启用 TLS，或运行在明确允许的加密隧道；
8. 防火墙只允许代理访问监听端口；
9. 代理和后端系统时间正常。

详细配置见[代理与后端子服](proxy-backend.md)。

## 快捷命令提示没有权限

- 发送者必须已绑定游戏角色，除非明确开启 `allow-unbound`；
- 绑定角色必须拥有快捷命令配置中的 `permission`；
- 离线权限查询需要 LuckPerms、Vault 或可用的离线 OP 信息；
- 代理本地命令与后端命令使用不同平台的权限系统；
- `permission` 留空只是不检查游戏权限，并不自动允许未绑定 QQ。

## 背包没有快照或材质

- 玩家至少进入过一次负责保存快照的后端；
- `inventory.enabled` 已开启；
- 群组服各端使用同一个 MySQL；
- 运行图片渲染的一端可以访问资源包、客户端核心或导出的图标；
- 历史快照可能需要玩家重新上线才能取得新版字段和玩家头数据。

完整说明见[背包查询与材质配置](inventory.md)。

## 图片中文显示异常

确认运行环境安装了 `image.font-name` 和 `inventory.font-name` 指定的字体。Linux 服务器通常没有 `Microsoft YaHei`，需要安装字体或改为系统中已有的中文字体。

## 重载失败

`/shitbot reload` 失败时，旧运行实例会继续保留。查看控制台中最早出现的配置或连接错误，修正后再次重载。

以下变更建议直接重启：

- 替换插件 JAR；
- 修改 Java 或服务端核心；
- 更换 TLS key store/trust store；
- 修改底层网络、防火墙或数据库服务。

## 仍然无法解决

在 [GitHub Issues](https://github.com/hutuyee/ShitBot/issues) 提交：

- ShitBot 版本；
- 平台与服务端版本；
- Java 版本；
- 单服、代理或代理—后端部署方式；
- 已去除 Token、密码、数据库地址和玩家隐私的日志；
- 可以稳定复现问题的步骤。

不要在 LLBot 群内请求 ShitBot 使用支持。
