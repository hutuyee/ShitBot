# ShitBot 你的新一代波特

> [!WARNING]
> 本插件由 AI 协助整理，内容仅供参考，请结合实际环境测试后使用。
>
> 已测试：
> - Spigot 1.8.8
>
> 插件目前仍处于开发阶段，无法 100% 保证在所有服务端核心、插件组合和命令场景下都完全一致。
>
> 如有问题请提交 issue 或联系作者。

ShitBot 是一个面向 Minecraft 服务器的QQ 机器人插件

欢迎使用 [LuckyLilliaBot](https://github.com/LLOneBot/LuckyLilliaBot) 来对接此框架

> **注意：ShitBot 不与 EasyBot 竞争，仅供 LLBot 在暂时不允许 EasyBot 连接时的应急使用。**

> **禁止在LLBot群讨论该插件的使用**

项目支持：

- Spigot(Bukkit) 全版本
- BungeeCord 全版本
- Velocity 全版本

---

## How To 安装？

- 下载你所需要的版本，若为群组服，仅需要下载一个插件装入BungeeCord/Velocity中

---

## 功能

## 命令

| 命令 | 说明 |
|---|---|
| `/shitbot status` | 查看 ShitBot 当前运行状态，包括数据库连接、OneBot WebSocket 连接和插件运行状态。 |
| `/shitbot migrate easybot [EasyBot.db]` | 迁移 EasyBot 的 QQ 绑定数据。数据库文件需要放在 ShitBot 插件目录中，文件名不填写时默认使用 `EasyBot.db`。 |
| `/shitbot image` | 手动生成一张当前服务器在线人数图片，用于测试图片生成功能。 |
| `/shitbot reload` | 热重载 ShitBot 配置，包括数据库、OneBot、QQ 指令和在线人数图片配置。 |

> 上述命令默认需要 `shitbot.admin` 权限。

### QQ 绑定

- 玩家未绑定 QQ 时自动阻止登录并生成一次性验证码
- 玩家可在 QQ 群中发送自定义绑定指令完成绑定
- 验证码支持有效期、最大尝试次数和自定义字符集
- 验证码使用随机盐哈希保存，数据库中不保存明文验证码
- 同一个 QQ 不能重复绑定多个游戏 ID

### OneBot v11

- 使用正向 WebSocket，由 ShitBot 主动连接 OneBot 实现
- 自动重连和指数退避
- 心跳超时检测
- OneBot API 调用回执与超时管理

### 群服互通

- 默认前缀 #qq/#mc 允许不使用前缀全部转发
```text
MC端输入: #qq 内容即可通过机器人传到群聊
```
同理
```text
QQ端输入: #mc 内容即可通过机器人传到MC
```

群消息中的图片会转成带 `OPEN_URL` 的可点击聊天组件。服主可在三端通用配置中选择显示方式：

```yaml
forwarding:
  group-to-game:
    # browser 或 picturebridge
    media-mode: "browser"
```

- `browser`：游戏里显示可点击的 `[图片]`，点击后由 Minecraft 按原版逻辑在浏览器查看。
- `picturebridge`：图片和表情会附加 PictureBridge 标记；装有 [PictureBridge](https://github.com/hutuyee/PictureBridge) 的客户端直接在聊天中预览，点击后在游戏内查看高清原图。
- 两种模式都会保留网页链接，所以没有安装模组的客户端也能点击后使用浏览器查看。
- 仓库根目录的 `PictureBridge` 是该客户端模组的 Git 子模块，GitHub 上可直接点击跳转到独立项目。

### [背包查询](https://github.com/hutuyee/ShitBot/docs/inventory.md)
### 数据库

- 支持 SQLite
- 支持 MySQL
- 自动执行数据库版本迁移
- Spigot、BungeeCord、Velocity 使用相同的数据表结构
- 可在不同平台版本之间迁移数据


## 在平台之间迁移数据

### 使用 MySQL

三个平台版本使用相同表结构。只需要让新平台插件连接原来的 MySQL 数据库即可。

建议迁移步骤：

1. 停止旧服务器或代理。
2. 备份数据库。
3. 将对应平台插件换成新版本。
4. 保持相同的 MySQL 配置。
5. 启动服务器并检查控制台迁移日志。

### 使用 SQLite

从 Spigot 迁移到 BungeeCord 或 Velocity 时：

1. 停止原服务器。
2. 找到原插件目录中的 `shitbot.db`。
3. 将数据库文件复制到新插件的数据目录。
4. 保持 `database.type: sqlite`。
5. 启动新平台。

不要在服务器运行期间直接复制 SQLite 数据库文件。

---

## 消息配置

所有主要回复和踢出信息都可以在 `messages` 中修改。

常用变量：

| 变量 | 含义 |
|---|---|
| `%player%` | 玩家游戏 ID |
| `%code%` | 本次生成的验证码 |
| `%qq%` | QQ 号 |
| `%expire_minutes%` | 验证码有效分钟数 |
| `%at%` / `%艾特%` | 在 QQ 回复中艾特发送者 |

Minecraft 消息支持 `&` 颜色代码。

---

## 管理命令

主命令：

```text
/shitbot
```

| 命令 | 功能 |
|---|---|
| `/shitbot status` | 查看数据库和 OneBot 状态 |
| `/shitbot reload` | 热重载配置和运行实例 |
| `/shitbot image` | 手动生成在线人数图片 |

Spigot 权限：

```text
shitbot.admin
```

Spigot 默认仅 OP 拥有该权限。BungeeCord 和 Velocity 同样检查 `shitbot.admin`。`status` 可以直接查看，`reload` 和 `image` 需要管理权限。

---

## 常见问题

### OneBot 一直显示 disconnected

检查：

- OneBot WebSocket 服务是否已经开启
- `websocket-url` 地址和端口是否正确
- Minecraft 服务器是否能访问 OneBot 所在主机
- `access-token` 是否与 OneBot 配置一致
- 防火墙是否允许对应端口
- OneBot 是否支持 OneBot v11 正向 WebSocket

### QQ 指令没有响应

检查：

- 群号是否在 `allowed-group-ids` 中
- 指令是否在 `aliases` 中
- OneBot 上报的是否为群消息
- 机器人是否拥有发送群消息和图片的权限
- 指令是否处于冷却时间

### 玩家绑定后仍然被拦截

检查：

- QQ 指令中的玩家名大小写是否完全一致
- 服务器与 QQ 机器人插件是否连接到同一个数据库
- 数据库中是否存在该玩家的绑定记录
- 数据库字符集和排序规则是否被手动修改

---

## 安全建议

- 不要把真实 OneBot Token 和数据库密码提交到公开仓库
- 生产环境建议限制 `allowed-group-ids`
- MySQL 账号只授予 ShitBot 数据库所需权限
- 切换平台或升级前先备份数据库
- 不要在多个独立插件实例中同时使用同一个 SQLite 文件
- 公开日志前检查是否包含 Token、数据库地址或账号信息

---

## License

本项目使用仓库中的 [LICENSE](LICENSE) 文件所声明的许可证(MIT)。
