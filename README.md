# ShitBot 你的新一代波特

[TODO](https://github.com/hutuyee/ShitBot/blob/main/TODO.md)

> [!WARNING]
> 本插件由 AI 协助整理，内容仅供参考，请结合实际环境测试后使用。
>
> 已测试：
> - Spigot 1.8.8
> - Spigot 1.12.2
> - Paper 1.21
> - Paper 1.21.11
> - Paper 26.1
> - Folia 1.21.11
> - CatServer 1.12.2
> - Velocity All version
> - BungeeCord git:BungeeCord-Bootstrap:26.1-R0.1-SNAPSHOT:68f3e54:2065
>
> 插件目前仍处于开发阶段，无法 100% 保证在所有服务端核心、插件组合和命令场景下都完全一致。
>
> 如有问题请提交 issue 或联系作者。

ShitBot 是一个面向 Minecraft 服务器的QQ 机器人插件

欢迎使用 [LuckyLilliaBot](https://github.com/LLOneBot/LuckyLilliaBot) 来对接此框架

> **注意：ShitBot 不与 EasyBot 竞争，仅供 LLBot 在暂时不允许 EasyBot 连接时的应急使用。**

> **禁止在LLBot群讨论该插件的使用**

项目支持：

- Spigot(Bukkit) 全版本（含 Folia 核心）
- BungeeCord 全版本
- Velocity 全版本

---

## How To 安装？

- 普通群组服只需在 BungeeCord/Velocity 安装代理版。
- 若要从 QQ 执行子服快捷命令或查询子服 TPS，还需在 Bukkit 子服安装 Spigot 版，并把子服 `config.yml` 的 `deployment.role` 改为 `backend`。
- 代理和子服必须连接同一个 MySQL 数据库；不要让多个实例同时使用同一个 SQLite 文件。

---

## 功能

## 命令

| 命令 | 说明 |
|---|---|
| `/shitbot status` | 查看 ShitBot 当前运行状态，包括数据库连接、OneBot WebSocket 连接和插件运行状态。 |
| `/shitbot update` | 后台下载最新平台 JAR，完成 SHA-256、独立 RSA 签名和 JAR 内容校验后备份并替换；手动重启后生效。 |
| `/shitbot migrate easybot [EasyBot.db]` | 迁移 EasyBot 的 QQ 绑定数据。数据库文件需要放在 ShitBot 插件目录中，文件名不填写时默认使用 `EasyBot.db`。 |
| `/shitbot image` | 手动生成一张当前服务器在线人数图片，用于测试图片生成功能。 |
| `/shitbot reload` | 热重载 `config.yml`、`commands.yml`、数据库、OneBot 和图片配置。 |

> 上述命令默认需要 `shitbot.admin` 权限。

### QQ 绑定

- 玩家未绑定 QQ 时自动阻止登录并生成一次性验证码
- 玩家可在 QQ 群中发送自定义绑定指令完成绑定
- 验证码支持有效期、最大尝试次数和自定义字符集
- 验证码使用随机盐哈希保存，数据库中不保存明文验证码

### QQ 快捷命令与 TPS

- 快捷命令白名单、别名、权限、执行位置、`latest.log` 抓取时间和回复模板统一配置在 `commands.yml`。
- 执行快捷命令前会以只读方式记录 `logs/latest.log` 的当前位置，捕获窗口结束后回传这期间所有插件追加的新日志，因此异步输出也能被取得；原日志文件不会被修改。回传副本会丢弃玩家聊天、上下线、连接/断开、玩家执行命令和 `[发送者: 命令反馈]` 等日志，将请求绑定玩家与在线玩家的名称、UUID、IPv4/IPv6 替换为占位符，再脱敏 Bearer、token、password、authorization、secret 和数据库 URL，并受 100 行 / 4000 字符上限约束。
- `target: backend` 在 Bukkit 子服执行；`target: proxy` 在 BungeeCord/Velocity 代理执行。
- 快捷命令或 TPS 后可直接加目标子服，例如 `lp编辑 survival`、`TPS lobby`；该参数会覆盖配置中的 `server`。
- 代理使用 `backend-transport.endpoints` 的独立鉴权通道，因此目标子服零玩家也能执行；没有配置对应端点时会拒绝执行，不会回退到未认证的插件消息。
- 带 `permission` 的快捷命令会检查绑定角色：在线时直接查询，离线时依次查询 LuckPerms、Vault 和离线 OP 状态。`permission` 留空只表示跳过游戏权限检查，默认仍要求 QQ 已绑定；只有同时显式设置 `allow-unbound: true` 才允许未绑定群成员调用，并会在启动时输出警告。
- 每个请求只选择一个子服，使用唯一请求 ID，并校验回包来源，因此不会在多个子服重复执行或重复回复。
- TPS 优先读取 EssentialsX，之后读取服务端原生 TPS，均不可用时使用 ShitBot 自己的 1/5/15 分钟采样。
- 代理 + 子服部署必须把 Spigot 端设为 `deployment.role: backend`，此模式不会连接 OneBot，因此不会与代理重复处理 QQ 消息。

独立通道需要在代理 `commands.yml` 的 `backend-transport.endpoints.<子服名>` 填写子服地址、端口和至少 16 位的随机密钥，并在对应子服的 `backend-transport.listener` 中填写相同端口、密钥和子服名后启用。listener 默认只接受 `127.0.0.1` / `::1`。HMAC 负责认证和完整性，每个连接还会使用服务端随机 challenge 阻止抓包跨重启重放，但 HMAC 本身不加密内容。

同机回环地址可以继续使用明文 Socket。跨机器时默认拒绝明文，必须在两端 `tls.enabled: true` 并配置 PKCS12 key/trust store；需要双向认证时，listener 再开启 `require-client-certificate` 并配置客户端 trust store。只有链路已经运行在 WireGuard、Tailscale 等加密隧道中且明确接受风险时，才应设置 `allow-insecure-remote-plaintext: true`。同时必须把 `allowed-proxy-addresses` 改为代理实际 IP，并通过防火墙限制来源；不要把端口直接暴露到公网。代理侧连接池固定为 2 个 worker、32 个等待项，队满立即拒绝，排队超时请求不会继续执行，连续连接失败会短暂熔断。

### [关于背包查询请点这里](https://github.com/hutuyee/ShitBot/blob/main/docs/inventory.md)
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
<img width="1920" height="1030" alt="160c91bb9aea6983d1021bb897358fe8" src="https://github.com/user-attachments/assets/357a8f0a-ba59-4c7f-a13a-9794868b1282" />

### 数据库

- 支持 SQLite
- 支持 MySQL
- 自动执行数据库版本迁移
- MySQL 迁移使用数据库级 `GET_LOCK`，代理与多个后端同时启动时只允许一个实例执行 DDL
- 启动时只在列定义确实不符合目标 schema 时执行修复性 `ALTER TABLE`
- Spigot、BungeeCord、Velocity 使用相同的数据表结构
- 可在不同平台版本之间迁移数据
- 远程 MySQL 默认必须启用 TLS（推荐 `sslMode=VERIFY_IDENTITY`）；明文连接需要显式风险开关
- JDBC 驱动优先复用服务器/代理核心已经提供的版本；旧版 Connector/J 的 `com.mysql.jdbc` API 会自动切换参数用法，不会把现代 TLS 校验模式静默降级
- 当前核心缺少所需 JDBC 驱动时，首次连接需要能访问 `repo.maven.apache.org`；插件会下载固定版本、校验文件大小与 SHA-256，并缓存到 `<插件数据目录>/libraries`，这些驱动不再塞进插件 JAR


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
| `/shitbot update` | 校验、备份并替换最新 Release JAR，等待手动重启 |
| `/shitbot image` | 手动生成在线人数图片 |

Spigot 权限：

```text
shitbot.admin
```

Spigot 默认仅 OP 拥有该权限。BungeeCord 和 Velocity 同样检查 `shitbot.admin`。`status` 可以直接查看，`reload`、`update`、`image` 和 `migrate` 需要管理权限。

插件启用时会在独立后台线程访问 GitHub，网络连接和读取均设置超时，不占用服务端主线程。最新 Release 信息仅在发生变化时写入插件目录下的 `update-cache.json`；有权限的管理员上线时会收到版本差异和可点击链接。

执行 `/shitbot update` 后，插件会从同一个 Release 中选择当前平台的 `ShitBotSpigot-*.jar`、`ShitBotBungee-*.jar` 或 `ShitBotVelocity-*.jar`。Release 必须同时包含对应的 `<JAR 文件名>.sha256` 和 `<JAR 文件名>.sig`；下载地址、文件大小、SHA-256、独立 RSA 签名、平台描述文件、主类和内嵌版本全部通过后，才会把当前 JAR 备份为同目录的 `.bak` 并替换。任何一步失败都会保留现有 JAR。替换后不会热重载插件，必须手动重启服务器或代理。

在 BungeeCord 执行该命令时，代理会使用现有的 HMAC 鉴权 Console Socket，把同一个 Release 的 Spigot JAR、checksum 和 detached signature 元数据下发给 `backend-transport.endpoints` 中的每个后端；各后端在自己的插件目录独立校验、备份和替换，并逐个向命令发送者返回结果。没有配置 endpoint 的子服不会被扫描或更新。后端模式的 Spigot 不会在启动时单独访问 GitHub，由 BungeeCord 统一检查并联动更新。

RSA 公钥已经作为 Core 资源内置到三个平台的最终插件 JAR，更新器直接从自身 JAR 加载，服主不需要下载、放置或配置任何密钥。插件内缺少公钥、Release 缺少签名资产或签名不匹配时，更新器会 fail closed。发布签名私钥只保存到 GitHub Actions 的 `SHITBOT_UPDATE_PRIVATE_KEY` 仓库 Secret，不能放进插件或提交到仓库。需要轮换密钥时，维护者必须同时替换 Core 内置公钥和仓库 Secret，并通过手动安装可信版本完成信任切换。

维护者首次生成或轮换密钥时可使用：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out update-private-key.pem
openssl pkey -in update-private-key.pem -pubout -out update-public-key.pem
```

---

## 常见问题

### OneBot 一直显示 disconnected

检查：

- OneBot WebSocket 服务是否已经开启
- `websocket-url` 地址和端口是否正确
- Minecraft 服务器是否能访问 OneBot 所在主机
- `access-token` 是否与 OneBot 配置一致
- 跨主机地址是否使用 `wss://`；远程 `ws://` 默认拒绝，除非显式开启风险开关
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
- 不要提交更新签名私钥；服主无需管理公钥，公钥随插件 JAR 内置
- 生产环境建议限制 `allowed-group-ids`
- MySQL 账号只授予 ShitBot 数据库所需权限
- 远程 MySQL 使用 `sslMode=VERIFY_IDENTITY`，远程 OneBot 使用 `wss://`
- 切换平台或升级前先备份数据库
- 不要在多个独立插件实例中同时使用同一个 SQLite 文件
- 公开日志前检查是否包含 Token、数据库地址或账号信息

---

## License

本项目使用仓库中的 [LICENSE](LICENSE) 文件所声明的许可证(MIT)。
