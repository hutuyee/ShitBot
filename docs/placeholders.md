# PlaceholderAPI 变量

ShitBot 有两个 PAPI 使用方向：向计分板、菜单等插件提供自己的状态变量，以及在高级图片模板中读取其他插件提供的变量。

## 在计分板或菜单显示 ShitBot 状态

在 Spigot/Paper/Folia 服务端安装兼容该服务端与 Java 版本的 PlaceholderAPI，再正常启动 ShitBotSpigot。ShitBot 会自动注册内置扩展，无需额外下载 `shitbot` expansion，也无需开启高级渲染组件。

| 变量 | 内容 |
| --- | --- |
| `%shitbot_version%` | ShitBot 插件版本 |
| `%shitbot_ready%` | 运行实例与数据库是否已就绪，`true` / `false` |
| `%shitbot_connected%` | 当前实例的 OneBot 是否已连接，`true` / `false`；backend 通常为 false |
| `%shitbot_whitelisted%` | 当前玩家是否有 ShitBot 白名单条目，包括无 QQ 白名单 |
| `%shitbot_bound%` | 当前玩家是否有 QQ 归属；无 QQ 白名单为 `false` |
| `%shitbot_qq%` | 当前玩家绑定的 QQ；无 QQ 白名单或未绑定时为空 |

玩家变量按精确游戏 ID 查询，区分大小写。PAPI 同步回调不会等待数据库：第一次读取会提交异步查询并返回空字符串，后续读取使用最长 5 秒的缓存；无玩家上下文、实例未就绪或查询失败也返回空字符串。缓存最多保存 512 个玩家，reload 后自动切换到新运行实例。它们适合展示信息，登录和命令授权仍由数据库业务接口决定。

`%shitbot_qq%` 会显示真实 QQ，请只放在你希望展示此信息的位置。它不会把无 QQ 白名单伪装成某个 QQ 的角色。

## 在图片里使用其他插件变量

高级模板的 `manifest.yml` 显式声明 `papi` 数据提供器：

```yaml
providers:
  - id: papi
    player: "${context.player}"
    server: "${context.server}"
    placeholders:
      display_name: "%player_displayname%"
      balance: "%vault_eco_balance_formatted%"
```

在 `scene.yml` 的文本节点写 `${data.papi.display_name}` 或 `${data.papi.balance}`。还需安装提供这些变量的插件及对应扩展。未解析的变量、缺少插件、离线玩家及超时会产生明确错误。

BungeeCord/Velocity 通过认证后端通道请求目标 Spigot 子服解析，代理不加载 Bukkit 的 PlaceholderAPI；`server` 使用代理中配置的子服名。Nukkit-MOT 不支持 Bukkit PAPI，可以用 ShitBot 内置数据提供器或第三方自定义提供器。

完整的查询限制、缓存、线程调度、错误显示和命令示例见[图片渲染与高级模板](image-templates.md#placeholderapi-与代理后端)。扩展接入方式遵循 [PlaceholderAPI 的内置扩展接口](https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/)。
