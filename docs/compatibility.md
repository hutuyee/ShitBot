# 平台兼容性

## 平台与 Java

| 平台模块 | 运行位置 | 插件最低 Java | 主要用途 |
| --- | --- | --- | --- |
| ShitBotSpigot | Spigot、Paper、Folia、CatServer 等 Bukkit 服务端 | 8 | 单服运行，或作为代理后的 Bukkit 后端 |
| ShitBotBungee | BungeeCord | 8 | 群组服代理、OneBot、跨服转发和后端命令调度 |
| ShitBotVelocity | Velocity | 21 | 群组服代理、OneBot、跨服转发和后端命令调度 |
| ShitBotNukkit | Nukkit-MOT | 17 | Nukkit-MOT 单服运行 |

服务端核心自身可能要求更高版本的 Java。例如现代 Paper 通常不能因为 ShitBotSpigot 兼容 Java 8 就改用 Java 8 启动。

## 已验证环境

当前仓库明确记录过以下验证环境：

- Spigot 1.8.8；
- Spigot 1.12.2；
- Paper 1.21；
- Paper 1.21.11；
- Paper 26.1；
- Folia 1.21.11；
- CatServer 1.12.2；
- BungeeCord `26.1-R0.1-SNAPSHOT` 构建线；
- Velocity 多版本运行线；
- Nukkit-MOT，模块当前编译依赖为 `1.26.40-R1`。

这些记录表示项目在对应环境中做过适配或验证，不代表任意服务端核心、插件组合和 Mod 混合环境都具有完全相同行为。出现兼容问题时，请提供精确核心名称、版本和完整启动日志。

## 平台功能差异

| 功能 | Spigot standalone | Spigot backend | BungeeCord / Velocity | Nukkit-MOT |
| --- | --- | --- | --- | --- |
| 直接连接 OneBot | 支持 | 不连接 | 支持 | 支持 |
| 玩家登录绑定检查 | 支持 | 支持 | 代理侧支持 | 支持 |
| 群服互通 | 支持 | 由代理负责 | 支持 | 支持文本和 URL |
| 保存在线背包快照 | 支持 | 支持 | 不直接读取在线背包 | 支持 |
| 从共享数据库生成背包图 | 支持 | 支持 | 支持 | 支持 |
| 代理—后端命令通道 | 可作为后端 | 监听代理请求 | 调度到后端 | 不使用 |
| PictureBridge 媒体标记 | 支持 | 由代理消息入口决定 | 支持 | 不使用 |

后端模式的 Spigot 不连接 OneBot，避免代理与子服重复回复同一条 QQ 消息。

## Folia

Spigot 模块在插件描述中声明 `folia-supported: true`。玩家背包字段会在 Bukkit 主线程或 Folia 玩家所属区域线程复制，数据库、资源扫描和图片渲染在异步线程执行。

其他插件通过非 Folia 安全方式修改背包、权限或日志时，仍可能影响对应联动功能。

## 可选依赖

ShitBot 的基础运行不强制要求这些插件，但安装后可以扩展权限或数据来源：

| 插件 | 使用位置 | 用途 |
| --- | --- | --- |
| LuckPerms | Spigot、BungeeCord、Velocity、Nukkit-MOT | 查询离线角色权限 |
| Vault | Spigot | LuckPerms 不可用时查询离线权限 |
| Essentials / EssentialsX | Spigot | 优先读取服务端 TPS 数据 |

没有可选权限插件时，在线玩家仍使用平台权限系统；离线角色的权限判断能力会受限。

## OneBot

ShitBot 使用 OneBot v11 正向 WebSocket，需要 OneBot 实现支持：

- 群消息事件；
- 发送群消息；
- 发送图片消息段；
- Action 回执；
- Access Token 鉴权时接受 `Authorization: Bearer` 请求头。

不同 OneBot 实现对文件、视频、语音、分享卡片和临时媒体 URL 的字段可能不同。ShitBot 会尽量转换为游戏内标签；无法取得 URL 时只显示可用摘要。

## PictureBridge

Java 客户端使用 `forwarding.group-to-game.media-mode: "picturebridge"` 时，可以安装独立的 [PictureBridge](https://github.com/hutuyee/PictureBridge) 客户端模组。

PictureBridge 不会被打包进 ShitBot 服务端 JAR。Nukkit-MOT 不使用该客户端协议。
