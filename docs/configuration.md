# 配置说明

ShitBot 首次启动会在插件数据目录生成：

- `config.yml`：OneBot、转发、绑定、数据库、图片、背包和消息配置；
- `commands.yml`：TPS、QQ 快捷命令和代理—后端命令通道。

两个文件都有默认注释。修改后执行 `/shitbot reload`；如果调整了插件 JAR、Java、TLS 证书或服务端核心，请正常重启实例。

## OneBot 连接

```yaml
onebot:
  enabled: true
  websocket-url: "ws://127.0.0.1:3001"
  access-token: ""
  allow-insecure-remote-websocket: false
  allow-all-groups: false
  allowed-group-ids:
    - 123456789
```

| 配置 | 说明 |
| --- | --- |
| `enabled` | 是否连接 OneBot |
| `websocket-url` | OneBot v11 正向 WebSocket 地址 |
| `access-token` | 非空时使用 `Authorization: Bearer <token>` 鉴权 |
| `allow-insecure-remote-websocket` | 是否允许非回环地址使用明文 `ws://`；默认拒绝 |
| `allow-all-groups` | 是否接受任意群的消息 |
| `allowed-group-ids` | 允许处理的群号，也是游戏消息转发到 QQ 的目标群 |

连接、Action、心跳和重连参数通常保持默认即可。网络延迟较大时，可以适当增加 `connect-timeout-seconds`、`action-timeout-seconds` 和 `heartbeat-timeout-seconds`。

## 群通知

通知位于 `onebot.notices`。

### 启动通知

```yaml
onebot:
  notices:
    server-startup:
      enabled: true
      target-server: ""
      check-interval-seconds: 5
      message: "服务器 %server% 已启动。"
```

- Spigot standalone 和 Nukkit-MOT：`target-server` 保持为空，在本实例启动并连接 OneBot 后通知。
- BungeeCord/Velocity：留空时通知代理启动；填写代理配置中的子服名时，代理会持续检查该子服，首次可连接后通知。
- 通知发送到全部 `allowed-group-ids`。

### 入群欢迎与退群解绑

```yaml
onebot:
  notices:
    group-join-welcome:
      enabled: true
      message: "欢迎 %at% 加入群聊！"
    group-leave-unbind:
      enabled: false
```

`group-leave-unbind` 会在成员退出允许群时删除其 QQ 绑定，属于破坏性业务规则，确认符合服务器规则后再开启。

## QQ 内置指令

`onebot.commands` 控制三个内置功能：

```yaml
onebot:
  commands:
    bind:
      enabled: true
      aliases: ["绑定", "/bind"]
    online-image:
      enabled: true
      aliases: ["服务器状态", "在线人数"]
    inventory:
      enabled: true
      aliases: ["背包", "我的背包"]
```

别名必须与群消息中的文本匹配。TPS 和其他快捷命令不在这里配置，而在 `commands.yml` 中配置。

## 群服互通

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

- `enabled` 分别控制两个转发方向。
- `require-prefix: true` 时，只转发带指定前缀的消息。
- `require-prefix: false` 时，转发所有非空消息。
- `prefix` 末尾空格属于前缀的一部分；设为空字符串时会转发全部非空消息。
- 游戏消息会发送到 `onebot.allowed-group-ids` 中的所有目标群。

`media-mode` 可选：

| 值 | 行为 |
| --- | --- |
| `browser` | Java 客户端显示可点击的媒体标签，在浏览器打开；Nukkit-MOT 显示标签和原始 URL |
| `picturebridge` | 为 Java 客户端的图片和表情添加 PictureBridge 标记；未安装模组的玩家仍可使用网页链接 |

`picturebridge` 需要玩家客户端安装 [PictureBridge](https://github.com/hutuyee/PictureBridge)。Nukkit-MOT 不使用该协议。

## 账号绑定

```yaml
binding:
  enabled: true
  allow-multiple-ids-per-qq: true
  maximum-ids-per-qq: 5
  code-length: 6
  expire-minutes: 10
```

- 未绑定玩家进入受绑定保护的服务端时，会收到一次性验证码。
- 玩家在 QQ 群发送 `绑定 <游戏ID> <验证码>` 完成绑定。
- 游戏 ID 大小写精确匹配。
- `allow-multiple-ids-per-qq: false` 时，每个 QQ 只能绑定一个游戏 ID。
- `maximum-ids-per-qq` 同时约束普通绑定与 EasyBot 数据迁移。

验证码尝试次数、冷却时间和字符集已有安全默认值，通常不需要修改。

## 数据库

```yaml
database:
  type: "sqlite"
  sqlite:
    file: "shitbot.db"
```

单实例可以使用 SQLite。代理与多个后端必须使用 MySQL，且全部实例连接同一个数据库。完整配置和迁移流程见[数据库与数据迁移](database.md)。

## 在线状态图片

`image` 控制在线人数图片的标题、服务器名、字体、宽度、每行玩家数、最大玩家数和头像服务。

常用项：

```yaml
image:
  title: "服务器在线状态"
  server-name: "Server-Status"
  font-name: "Microsoft YaHei"
  players-per-row: 5
  maximum-players: 200
  avatar:
    enabled: true
    url-template: "https://mc-heads.net/avatar/%player%/64"
```

Linux 环境中必须安装 `font-name` 指定的字体，否则中文可能回退或显示异常。Nukkit-MOT 有 Bedrock 玩家时，可将头像地址替换为支持 Xbox ID 的服务。

## 背包查询

`inventory` 控制快照间隔、离线保留时间、渲染并发和材质来源。群组服需要让后端保存快照，并让代理通过共享 MySQL 读取快照。

材质包、客户端 JAR、Mod 物品和自定义图标的配置见[背包查询与材质配置](inventory.md)。

## 自定义消息

`messages` 中可以修改主要回复和踢出信息。常用变量：

| 变量 | 含义 |
| --- | --- |
| `%player%` | 游戏 ID |
| `%code%` | 绑定验证码 |
| `%qq%` | QQ 号 |
| `%expire_minutes%` | 验证码有效时间 |
| `%maximum_ids%` | 单个 QQ 最大绑定数 |
| `%at%` / `%艾特%` | 在 QQ 回复中艾特发送者 |

Minecraft 消息支持 `&` 颜色代码。YAML 多行消息应使用 `|`，并保持后续行缩进一致。

## commands.yml

`commands.yml` 包含：

- QQ 快捷命令总开关和冷却；
- TPS 指令；
- 自定义快捷命令、权限、执行位置和回复模板；
- BungeeCord/Velocity 到 Spigot 后端的认证通道。

命令配置见[命令与权限](commands.md)，代理通道见[代理与后端子服](proxy-backend.md)。
