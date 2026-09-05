# 命令与权限

## 管理命令

主命令为 `/shitbot`，别名为 `/sbot`。

| 命令 | 功能 | 权限 |
| --- | --- | --- |
| `/shitbot status` | 查看数据库、OneBot 和运行状态 | 无 |
| `/shitbot reload` | 重载配置、数据库、OneBot、图片和命令服务 | `shitbot.admin` |
| `/shitbot update` | 下载并安装当前平台的新版本 | `shitbot.admin` |
| `/shitbot image` | 手动生成在线状态图片 | `shitbot.admin` |
| `/shitbot migrate easybot [EasyBot.db]` | 导入 EasyBot 绑定数据 | `shitbot.admin` |

Spigot 和 Nukkit-MOT 默认仅 OP 拥有 `shitbot.admin`。BungeeCord 与 Velocity 使用平台权限系统检查同名权限。

`/shitbot reload` 会先创建新的运行实例；新配置加载失败时保留旧实例。插件 JAR 更新、Java 变更和 TLS 文件变更仍建议正常重启。

## QQ 绑定

默认别名：

```text
绑定 <游戏ID> <验证码>
/bind <游戏ID> <验证码>
```

验证码由未绑定玩家进入服务器时生成。QQ 和游戏 ID 的绑定规则由 `binding` 配置控制。

## 在线状态与背包

默认指令：

```text
服务器状态
在线人数
背包
我的背包
背包 <游戏ID>
我的背包 <游戏ID>
```

指定游戏 ID 查询背包时，ShitBot 会确认该角色已绑定到消息发送者的 QQ，不能查询他人的角色。

## TPS 指令

TPS 在 `commands.yml` 中配置：

```yaml
tps:
  enabled: true
  permission: ""
  server: ""
```

别名、成功文本和失败文本在所选语言文件的 `console.tps` 中配置。

- 单服部署和 Nukkit-MOT 在本服查询。
- 代理部署可以在 `server` 中设置默认子服。
- 群成员也可以在指令后指定子服，例如 `TPS survival`。
- `permission` 为空时不检查游戏权限，但仍受群范围和命令冷却限制。

## QQ 快捷命令

每个快捷命令放在 `commands.yml` 的 `shortcuts` 下：

```yaml
shortcuts:
  luckperms-editor:
    enabled: true
    command: "lp editor"
    permission: "shitbot.admin"
    allow-unbound: false
    target: "backend"
    server: ""
    capture-seconds: 5
```

内置快捷命令的别名和回复模板在语言文件的 `console.shortcuts.<名称>` 中配置。新增自定义快捷命令时，如果语言文件中没有同名条目，会使用 `commands.yml` 中的 `aliases`、`message` 和 `failed` 作为后备。

| 配置 | 说明 |
| --- | --- |
| `aliases` | QQ 群内触发文本 |
| `command` | 去掉前导 `/` 的控制台命令 |
| `permission` | 绑定角色必须拥有的游戏权限；留空表示跳过游戏权限检查 |
| `allow-unbound` | 是否允许未绑定 QQ 调用；默认关闭 |
| `target` | `backend` 在 Bukkit 子服执行，`proxy` 在 BungeeCord/Velocity 执行 |
| `server` | 默认目标子服；留空时优先使用绑定角色所在子服 |
| `capture-seconds` | 执行后等待并收集新增控制台日志的时间 |

即使 `permission` 为空，默认仍要求发送者已经绑定游戏角色。只有明确设置 `allow-unbound: true` 才允许未绑定群成员执行，该配置会降低权限边界，应谨慎使用。

群成员可以在快捷命令后指定目标子服，例如：

```text
lp编辑 survival
```

该参数会覆盖命令中的 `server`。

## 权限检查顺序

Spigot 后端执行带 `permission` 的快捷命令时：

1. 角色在线时使用 Bukkit 权限系统；
2. 角色离线时依次尝试 LuckPerms、Vault 和离线 OP 状态。

代理本地命令使用代理权限系统；离线权限可由代理上的 LuckPerms 提供。Nukkit-MOT 在线角色使用 Nukkit 权限系统，离线角色使用 LuckPerms（如已安装）。

## 命令输出

快捷命令执行前会记录日志位置，等待 `capture-seconds` 后读取这段时间新增的内容：

- Bukkit 使用 `logs/latest.log`；
- Nukkit-MOT 使用 `logs/server.log`；
- 原始日志文件不会被修改；
- 单次回复最多保留 100 行、4000 个字符；
- 玩家聊天、上下线、执行命令等日志会从回复副本中移除；
- 玩家名、UUID、IP、Token、密码和数据库地址会在回复副本中脱敏。

执行成功但没有产生新日志时，机器人会返回明确提示。

## 修改后生效

保存 `commands.yml` 或语言文件后执行：

```text
/shitbot reload
```

如果是代理—后端部署，代理和相关后端的配置都修改完成后再分别重载。通道地址、证书或防火墙发生变化时建议重启。
