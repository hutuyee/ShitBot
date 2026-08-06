# QQ 自助背包查询

## 使用方式与权限边界

群成员只能发送 `背包` 或 `我的背包` 查询自己 QQ 已绑定的角色。命令不接受游戏 ID 参数，发送 `背包 Steve` 只会返回用法提示，不会查询其他玩家。

若一个 QQ 绑定了多个角色，系统按以下顺序选择：

1. 当前在线并能实时抓取背包的角色；
2. 没有在线角色时，选择数据库中快照时间最新的角色。

查询流程会合并同一 QQ 的并发请求，并复用短时渲染缓存。

## 离线快照

Spigot 后端会：

- 每隔 `inventory.snapshot.interval-seconds` 秒抓取所有在线玩家；
- 玩家退出时再抓取一次；
- 实时查询成功后异步写入一次。

快照以版本化 JSON、GZIP 压缩 BLOB 保存到 `shitbot_inventory_snapshots`。只保存绘图所需字段，不保存完整 NBT。超过 `retention-days` 的快照不会再返回，并会被后台删除。

## 群组服部署

代理端没有玩家背包。群组服请让代理端与所有后端 Spigot 使用同一个 MySQL 数据库：

- 后端 Spigot 可关闭 `onebot.enabled`，但保留 `inventory.enabled`，负责定时和退出快照；
- BungeeCord/Velocity 负责接收 QQ 命令、读取共享快照和生成图片；
- SQLite 只适合单服，不能作为多进程共享数据库。

代理端生成图片时也必须能访问物品图标。推荐把客户端导出的 `item-icons` 目录复制到代理插件数据目录，而不是把完整 Mod 放进代理运行目录。

## Mod 与资源包图标

解析优先级：

1. `item-icons/<namespace>/<path>__cmd_<custom-model-data>.png`；
2. `item-icons/<namespace>/<path>.png`；
3. `inventory.icons.resource-archives`；
4. `inventory.icons.mods-directory` 下的 JAR/ZIP。

自动解析只处理 `item/generated`、`item/handheld` 一类普通二维模型，并支持传统 `overrides` 中的 `custom_model_data`。复杂 3D 模型、动态 NBT、流体容器、动画和 Mod 自定义渲染器应由客户端使用 Minecraft 自身渲染器导出 PNG，再放入 `item-icons`。

路径示例：

```text
plugins/ShitBot/item-icons/
├─ minecraft/diamond.png
├─ create/precision_mechanism.png
└─ example/custom_item__cmd_10001.png
```

找不到可用图标时会显示缺失材质占位图，并保留格子、数量和耐久信息。

## 性能建议

- 单服默认每 60 秒写一次快照，通常无需缩短；
- 大型群组服可把 MySQL `database.async-threads` 调到 4-8；
- `render.maximum-concurrent` 建议保持 2-4；
- 图标缓存和快照缓存均有上限，不会无限增长；
- 资源索引在后台懒加载，不阻塞 Bukkit 主线程；
- Bukkit 主线程只复制背包字段，压缩、数据库、图标解析和图片渲染均在异步线程执行。
