# ShitBot

连接 Minecraft 服务器与 QQ 群的 OneBot v11 插件，提供账号绑定、群服互通、在线状态图片、背包查询、TPS 查询和受控的 QQ 快捷命令。

支持 Spigot/Paper/Folia、BungeeCord、Velocity 和 Nukkit-MOT。OneBot 实现可以使用 [LuckyLilliaBot](https://github.com/LLOneBot/LuckyLilliaBot) 或其他兼容 OneBot v11 正向 WebSocket 的实现。

## 下载

从 [GitHub Releases](https://github.com/hutuyee/ShitBot/releases) 下载当前平台的 JAR：

| 运行平台 | 下载文件 | 放置目录 | Java |
| --- | --- | --- | --- |
| Spigot、Paper、Folia、CatServer 等 Bukkit 服务端 | `ShitBotSpigot-*.jar` | `plugins/` | 8+ |
| BungeeCord | `ShitBotBungee-*.jar` | `plugins/` | 8+ |
| Velocity | `ShitBotVelocity-*.jar` | `plugins/` | 21+ |
| Nukkit-MOT | `ShitBotNukkit-*.jar` | `plugins/` | 17+ |

只安装与你的平台匹配的 JAR，不要将多个平台版本放进同一个实例。

## 安装

1. 停止服务器或代理。
2. 将对应平台的 JAR 放入 `plugins/`。
3. 启动一次，让 ShitBot 生成 `config.yml` 和 `commands.yml`。
4. 停止实例并修改配置。
5. 再次启动，然后执行 `/shitbot status` 检查数据库和 OneBot 连接状态。

不要使用插件管理器热加载或热卸载 ShitBot。修改配置时使用 `/shitbot reload`，升级 JAR 后正常重启服务器或代理。

## 最小配置

在插件数据目录的 `config.yml` 中填写 OneBot 地址、Token 和允许使用的 QQ 群：

```yaml
onebot:
  enabled: true
  websocket-url: "ws://127.0.0.1:3001"
  access-token: ""
  allow-all-groups: false
  allowed-group-ids:
    - 123456789
```

ShitBot 使用 OneBot v11 正向 WebSocket，由插件主动连接 OneBot。跨主机连接应使用 `wss://`。

如需开启群服互通，再修改：

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

配置完成后执行：

```text
/shitbot reload
```

## 选择部署方式

| 场景 | 安装方式 | 数据库 |
| --- | --- | --- |
| 单个 Bukkit 服务端 | 只安装 Spigot 版，保持 `deployment.role: "standalone"` | SQLite 或 MySQL |
| BungeeCord/Velocity 群组服 | 在代理安装对应代理版 | 推荐 MySQL |
| 代理接收 QQ 命令并在子服执行 | 代理安装对应版本，每个目标子服安装 Spigot 版并设为 `deployment.role: "backend"` | 必须共用 MySQL |
| Nukkit-MOT 单服 | 只安装 Nukkit 版 | SQLite 或 MySQL |

代理与后端模式还需要配置 `commands.yml` 中的 `backend-transport`。不要让多个插件实例同时使用同一个 SQLite 文件。

## 常用命令

| 命令 | 功能 | 权限 |
| --- | --- | --- |
| `/shitbot status` | 查看数据库、OneBot 和插件运行状态 | 无 |
| `/shitbot reload` | 重载 `config.yml`、`commands.yml` 和运行实例 | `shitbot.admin` |
| `/shitbot update` | 下载并校验当前平台的新版本，替换后等待手动重启 | `shitbot.admin` |
| `/shitbot image` | 生成一次在线状态图片 | `shitbot.admin` |
| `/shitbot migrate easybot [EasyBot.db]` | 导入 EasyBot 绑定数据 | `shitbot.admin` |

Spigot 和 Nukkit-MOT 默认仅 OP 拥有 `shitbot.admin`。BungeeCord 与 Velocity 同样检查该权限。

## QQ 群内使用

默认指令和前缀：

| 输入位置 | 示例 | 功能 |
| --- | --- | --- |
| QQ 群 | `绑定 Steve ABC123` | 绑定游戏账号 |
| QQ 群 | `服务器状态` | 获取在线状态图片 |
| QQ 群 | `背包` 或 `背包 Steve` | 查询自己绑定角色的背包 |
| QQ 群 | `TPS` | 查询服务器 TPS |
| QQ 群 | `lp编辑` | 执行配置的快捷命令 |
| Minecraft | `#qq 内容` | 将游戏消息转发到 QQ 群 |
| QQ 群 | `#mc 内容` | 将群消息转发到游戏 |

别名、权限、目标子服和快捷命令内容可以在 `config.yml` 与 `commands.yml` 中修改。

## 文档

- [安装与部署](docs/installation.md)
- [配置说明](docs/configuration.md)
- [命令与权限](docs/commands.md)
- [代理与后端子服](docs/proxy-backend.md)
- [数据库与数据迁移](docs/database.md)
- [背包查询与材质配置](docs/inventory.md)
- [常见问题](docs/troubleshooting.md)
- [全部文档](docs/README.md)
- [待办事项](TODO.md)

## 支持与反馈

项目仍在开发中。遇到问题请通过 [GitHub Issues](https://github.com/hutuyee/ShitBot/issues) 反馈，并附上运行平台、服务端版本、Java 版本和相关日志。

ShitBot 不与 EasyBot 竞争；请勿在 LLBot 群内讨论本插件的使用问题。

## License

[MIT](LICENSE)
