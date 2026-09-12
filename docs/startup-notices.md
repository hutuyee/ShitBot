# 服务器启动提醒

管理员可以选择本实例启动后通知，或由代理等待一个指定子服上线后通知。消息只发送到 `onebot.allowed-group-ids` 明确列出的群，`allow-all-groups: true` 不会自动扩大通知范围。

## 代理启动就通知

在 BungeeCord 或 Velocity 的 `config.yml` 配置：

```yaml
onebot:
  enabled: true
  allowed-group-ids:
    - 123456789
  notices:
    server-startup:
      enabled: true
      target-server: ""
      check-interval-seconds: 5
```

保持 `target-server` 为空。ShitBot 运行实例启动完成且 OneBot 连上后发送一次。Spigot standalone 与 Nukkit-MOT 也使用这一配置，通知会交回平台线程执行；Spigot backend 不自行连接 OneBot 或发送通知。

## 等指定子服上线再通知

只在 BungeeCord/Velocity 上配置：

```yaml
onebot:
  notices:
    server-startup:
      enabled: true
      target-server: "survival"
      check-interval-seconds: 5
```

`survival` 必须是代理配置中的子服名。代理每 5 秒尝试一次 Minecraft 状态 ping，首次收到有效响应后停止检查并触发通知。它可以在代理启动之后才启动，也可以已经在线。未配置该子服时会在日志提示；Spigot 和 Nukkit 不接受非空 `target-server`。

此处“上线”的判断是子服可响应状态 ping，不代表所有第三方插件、世界预生成或业务初始化已完成。本功能是代理本次启动的一次通知，子服以后每次重启不会再次触发。

## 通知内容与重载

编辑当前语言文件 `lang/<language>.yml`：

```yaml
notices:
  server-startup: "服务器 %server% 已启动，可以加入了！"
```

`%server%` 为目标子服名或当前平台名，`%platform%` 为 ShitBot 所在平台名。

`/shitbot reload` 不会重新发送已经完成的启动通知。目标尚未上线时，重载会按新配置重新等待；已经触发但 OneBot 未连接、或有群发送失败时，待发送状态及已成功群列表会保留到新运行实例，在下次连接后补发未确认的群。网络中断造成 OneBot 的发送结果无法确认时，补发可能重复，无法保证跨网络故障的严格只发送一次。通知状态只保存在本次插件进程中，正常重启会开始新一轮启动通知。
