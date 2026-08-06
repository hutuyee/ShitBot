# QQ 背包查询

## 使用方式与权限边界

群成员只能发送 `背包` 或 `我的背包` 查询自己 QQ 已绑定的角色。config可改命令

若群成员有多个号，则会查询该查询号是否为他所绑定号。

## 支持离线查询

## 群组服部署

代理端没有玩家背包。群组服请让代理端与所有后端 Spigot 使用同一个 MySQL 数据库：

- 后端 Spigot 可关闭 `onebot.enabled`，但保留 `inventory.enabled`，负责定时和退出快照；
- BungeeCord/Velocity 负责接收 QQ 命令、读取共享快照和生成图片；
- SQLite 只适合单服，不能作为多进程共享数据库。

代理端生成图片时也必须能访问同一套物品资源。可把客户端核心、资源包和 Mod JAR 放在代理可访问的位置，并在 `resource-archives` 中写绝对路径；也可以复制精确导出的 `item-icons`。

## 跨版本材质解析

解析优先级：

1. `item-icons/<namespace>/<path>__cmd_<custom-model-data>.png`；
2. `item-icons/<namespace>/<path>__data_<旧版数据值>.png`；
3. `item-icons/<namespace>/<path>.png`；
4. `inventory.icons.resource-archives` 中显式配置的目录、资源包、客户端核心或资源 JAR；
5. 自动发现的 `resources`、`resourcepacks`、`server-resource-packs`、`mods`、`versions` 和客户端 JAR。

ShitBot 的 Spigot 运行兼容线从 1.8.8 开始；材质解析器覆盖 1.8.8 到当前 26.x 使用过的资源格式，并兼容旧资源包中的直接贴图目录：

- 旧资源包的 `textures/items`、`textures/blocks` 直接贴图；
- 1.8 起的 JSON 物品和方块模型，包括旧版 `builtin/generated` / `builtin/handheld`；
- 1.8-1.12 Bukkit 材质名、旧注册名和 data value 变种；
- 1.13 扁平化后的命名空间物品 ID；
- 传统模型 `overrides` 中的 `custom_model_data`、`damage` 和 `damaged`；
- 1.21.4 到 26.x 的 `assets/<namespace>/items/*.json`：model、composite、condition、range_dispatch、select 和 special 基础模型；
- 新版 CustomModelData 的 floats、flags、strings、colors 有界快照；
- 1.8 到当前版本的 GameProfile、PlayerProfile 和 ResolvableProfile 玩家头贴图。

普通二维物品会合成全部 `layer0...layer15`。普通方块模型会从继承后的 top/side/face 材质生成等距图标。动画 PNG 使用第一帧，避免整条动画被压缩到一个格子里。

### 客户端核心放在哪里

ShitBot 不携带 Mojang 原版材质。要显示原版物品，运行图片渲染的一端必须能读取对应版本的客户端核心或完整原版资源包。

单服默认目录：

```text
server/
├─ mods/
│  └─ 1.8.9-client.jar
└─ plugins/ShitBot/
```

默认 `mods-directory: ../../mods` 会从 `plugins/ShitBot` 解析到上面的 `server/mods`。文件必须是包含 `assets/minecraft` 的客户端 JAR；Forge/OptiFine 安装器、启动器核心或只有 class 的裁剪包不能代替客户端资源。

也可显式配置，优先级更清楚：

```yaml
inventory:
  icons:
    resource-archives:
      - "D:/Minecraft/versions/1.8.9/1.8.9.jar"
      - "D:/Minecraft/resourcepacks/server-pack.zip"
```

资源索引启动时异步建立；首次查询最多等待 `index-wait-ms`。每隔 `refresh-seconds` 检查文件时间和大小，新增或替换 JAR 后无需因负缓存永久显示缺失材质。


### 玩家头

Spigot 后端会兼容读取旧版 CraftMetaSkull/GameProfile、新版 PlayerProfile/ResolvableProfile 中的 `textures` 属性，只截取并保存 `textures.minecraft.net` 的十六进制内容哈希。图片端通过固定的 Mojang 贴图域名获取皮肤，合成头部底层和帽子层，并缓存在：

```text
plugins/ShitBot/inventory-head-cache/
```

缓存启动时异步清理，最多保留 4096 个文件或 64 MiB。服务器无法访问 `textures.minecraft.net` 时会暂时回退到资源包里的普通玩家头图标，并在 30 秒后重试，不会阻塞 Bukkit 主线程。

格式 1、2 的历史离线快照没有玩家头贴图哈希。升级后需要让该角色上线一次、等待定时快照，或在在线状态查询一次，新的格式 3 快照才会显示自定义头。

## Mod 和特殊渲染器边界

Forge、NeoForge 和常见混合端会尝试从 NMS/Loader 注册表反射得到真实的 `modid:item_name`，再读取 Mod JAR 的 `assets`。普通 JSON 模型可以自动显示。

以下外观由任意客户端代码或实时上下文生成，服务端通用解析器不可能仅靠资源文件完全复刻：

- TESR/BEWLR、ISTER、Fabric BuiltinItemRenderer 等自定义渲染器；
- 根据完整 NBT、能力、流体、能量或客户端状态动态改变的模型；
- 地图内容、旗帜、盾牌图案等需要世界或完整图案上下文的特殊模型；
- 自定义 OBJ/GLTF 加载器和着色器效果。

这类物品使用客户端实际渲染后导出的 PNG 覆盖：

```text
plugins/ShitBot/item-icons/
├─ minecraft/diamond.png
├─ create/precision_mechanism.png
├─ example/custom_item__cmd_10001.png
└─ minecraft/wool__data_14.png
```

找不到可用图标时才显示缺失材质占位图，并保留格子、数量和耐久信息。

## 性能建议

- 单服默认每 60 秒写一次快照，通常无需缩短；
- 大型群组服可把 MySQL `database.async-threads` 调到 4-8；
- `render.maximum-concurrent` 建议保持 2-4；
- 材质索引使用单独线程，不会和 PNG 渲染线程互相死锁；
- 图标缓存带资源索引代数，资源更新后旧结果自动失效；
- 空图标只短暂负缓存 30 秒；
- 图标、快照和渲染缓存均有上限，不会无限增长；
- Bukkit 主线程只复制背包字段；压缩、数据库、资源扫描和图片渲染均在异步线程执行。
