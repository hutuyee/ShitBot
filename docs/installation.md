# 安装与部署

## 运行要求

ShitBot 需要一个支持 OneBot v11 正向 WebSocket 的 QQ 机器人实现。插件会主动连接 OneBot，不接受反向 WebSocket 连接。

可以使用 [LuckyLilliaBot](https://github.com/LLOneBot/LuckyLilliaBot) 或其他兼容实现。安装前准备好：

- OneBot 正向 WebSocket 地址；
- OneBot Access Token，如果 OneBot 已启用鉴权；
- 允许使用机器人的 QQ 群号；
- 与运行平台匹配的 ShitBot JAR。

## 选择平台 JAR

从 [GitHub Releases](https://github.com/hutuyee/ShitBot/releases) 下载：

| 平台 | JAR | 插件运行时 Java |
| --- | --- | --- |
| Spigot、Paper、Folia、CatServer 等 Bukkit 服务端 | `ShitBotSpigot-*.jar` | Java 8+ |
| BungeeCord | `ShitBotBungee-*.jar` | Java 8+ |
| Velocity | `ShitBotVelocity-*.jar` | Java 21+ |
| Nukkit-MOT | `ShitBotNukkit-*.jar` | Java 17+ |

这里列的是 ShitBot JAR 的最低字节码要求。服务端核心自身可能要求更高版本的 Java，应同时满足核心要求。某个 Release 没有对应平台 JAR 时，表示该版本没有发布该平台构建，不能改用其他平台 JAR。

## 首次启动

1. 停止服务器或代理。
2. 将对应平台 JAR 放入该实例的 `plugins/` 目录。
3. 启动实例，等待 ShitBot 生成插件数据目录。
4. 停止实例。
5. 打开生成的 `config.yml` 和 `commands.yml`。
6. 按下文完成最小配置后重新启动。

不要修改 JAR 内部的默认配置。后续版本增加配置项时，应先备份当前配置，再对照新版本生成的配置补充内容。

## OneBot 最小配置

编辑 `config.yml`：

```yaml
onebot:
  enabled: true
  websocket-url: "ws://127.0.0.1:3001"
  access-token: ""
  allow-all-groups: false
  allowed-group-ids:
    - 123456789
```

- OneBot 和 Minecraft 实例在同一台机器时，可以使用 `ws://127.0.0.1:<端口>`。
- 跨主机部署时应使用 `wss://`，并确保 Java 信任服务端证书。
- `access-token` 必须与 OneBot 配置一致。
- `allowed-group-ids` 为空且 `allow-all-groups: false` 时，所有群消息都会被拒绝。
- 不建议在生产环境使用 `allow-all-groups: true`。

启动后执行：

```text
/shitbot status
```

确认输出中的数据库和 OneBot 状态正常，再继续开启转发、绑定、图片或快捷命令功能。

## 单个 Bukkit 服务端

只安装 `ShitBotSpigot-*.jar`，保持：

```yaml
deployment:
  role: "standalone"
```

此模式由 Spigot 插件直接连接 OneBot。单实例可以使用默认 SQLite，也可以改用 MySQL。不需要配置 `backend-transport`。

## BungeeCord 或 Velocity 群组服

只需要群聊转发、QQ 绑定和代理侧功能时，在代理安装 `ShitBotBungee-*.jar` 或 `ShitBotVelocity-*.jar`。

如果还需要从 QQ 查询具体子服 TPS、执行子服快捷命令或由后端保存背包快照，则需要：

1. 在代理安装对应代理版；
2. 在每个目标 Bukkit 子服安装 Spigot 版；
3. 将后端 Spigot 的 `deployment.role` 设为 `backend`；
4. 让代理与所有后端连接同一个 MySQL 数据库；
5. 配置 `commands.yml` 中的认证命令通道。

完整步骤见[代理与后端子服](proxy-backend.md)。

## Nukkit-MOT 单服

将 `ShitBotNukkit-*.jar` 放入 Nukkit-MOT 的 `plugins/` 目录。Nukkit-MOT 是单服平台：

- 不使用 `deployment.role`；
- 不配置代理—后端命令通道；
- TPS 和 QQ 快捷命令始终在本服执行；
- 可以使用 SQLite 或 MySQL；
- 不使用 PictureBridge 客户端协议，媒体标签后会保留原始 URL。

## 开启群服互通

两种转发方向默认都关闭。需要时在 `config.yml` 中分别开启：

```yaml
forwarding:
  game-to-group:
    enabled: true
    require-prefix: true
    prefix: "#qq "
  group-to-game:
    enabled: true
    require-prefix: true
    prefix: "#mc "
    media-mode: "browser"
```

默认用法：

```text
Minecraft: #qq 要发送到 QQ 群的内容
QQ 群:     #mc 要发送到游戏的内容
```

修改完成后执行 `/shitbot reload`。更多选项见[配置说明](configuration.md)。

## 安装完成后的检查

建议依次确认：

1. `/shitbot status` 显示数据库可用；
2. OneBot 状态为已连接；
3. 机器人所在群已列入 `allowed-group-ids`；
4. 在 QQ 群发送 `服务器状态` 能收到回复；
5. 开启转发后，`#qq` 和 `#mc` 两个方向均符合预期；
6. 使用代理—后端模式时，`TPS <子服名>` 能到达指定子服。

发生问题时见[常见问题](troubleshooting.md)。
