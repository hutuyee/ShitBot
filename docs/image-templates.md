# 图片渲染与高级模板

ShitBot 提供两条互不混装的图片路径。默认模式面向普通服务器，高级模式面向需要自由布局、真实数据预览和插件 API 的服务器。

## 两种模式

| 模式 | 配置 | 运行内容 | 网络下载 | 适用场景 |
| --- | --- | --- | --- | --- |
| 内置 Java 图片 | `image.renderer: "java"` | 平台插件内已有的 Java2D 在线图；外观来自 `templates/*.yml` | 不下载高级渲染组件 | 希望占用低、配置简单的服务器 |
| 高级场景模板 | `custom-image-templates.enabled: true` | 独立的 `ShitBotRenderer` 组件、`image-templates/` 场景、数据提供器和可选编辑器 | 首次启用且本地无有效缓存时下载 | 需要自由图层、条件/循环、PAPI 或第三方插件调用的服务器 |

当前高级渲染器仍然使用 Java2D，不包含 Chromium、Node.js、React 运行时，也不会执行模板上传的 HTML、JavaScript 或服务器命令。平台插件 JAR 与可选渲染器 JAR 分开发布，模板图片和资源只放在插件数据目录。

### 只使用内置图片

保持默认值即可：

```yaml
image:
  renderer: "java"
  template: "default"

custom-image-templates:
  enabled: false
```

此时不会创建高级模板引擎、不会启动编辑器，也不会访问渲染组件下载地址。在线图继续读取 `templates/<名称>.yml`；背包图也继续使用该轻量主题格式。

### 原生图片外观和底稿

新安装使用深蓝与青绿色的默认主题，在线图和背包图采用统一的卡片、边框和状态色。已有 `templates/default.yml` 会保留管理员的修改；升级后希望使用新外观时，可从新版本的同名资源复制所需颜色字段到自己的主题。

每次启动和 `/shitbot reload`，在线图与背包图都会在图片线程预生成静态底稿。后续渲染复制底稿再填入动态数据：在线人数、头像、名称、物品、耐久和时间不会写回底稿，因此玩家离开或物品消失时不会留下旧内容。

在线图按子服名称、面板高度和玩家徽章布局缓存底稿；人数或名称宽度改变布局时生成新的底稿。背包图复用固定格子和装备标签。每种原生图片的底稿缓存最多保留 4 项、合计 8,388,608 像素，采用内存缓存；大于此限的图片仍可渲染，但底稿不常驻缓存。重载与重启创建新缓存，自动使用最新主题、语言和尺寸。现有成品 PNG 缓存仍按 `image.cache-seconds` 与背包渲染缓存配置生效。

### 让内置“服务器状态”指令使用高级模板

```yaml
image:
  renderer: "custom"
  custom-template: "online-status"

custom-image-templates:
  enabled: true
```

`image.renderer: "custom"` 但 `custom-image-templates.enabled: false` 属于无效配置，插件会明确拒绝加载，避免配置看起来启用了高级模板但实际静默回退。

也可以保持 `image.renderer: "java"`，只把 `custom-image-templates.enabled` 设为 `true`。这样默认在线图仍走轻量路径，而编辑器、自定义群命令和其他插件的 `ShitBotApi` 可以使用高级模板。

## 按需下载与缓存

高级模板总开关关闭时，下载逻辑不会运行。开启后，插件按以下顺序加载：

1. 读取 `custom-image-templates.component.version`；留空时使用当前平台插件版本；
2. 检查 `components/image-renderer/<版本>/` 下的缓存；
3. 同时校验 JAR 大小、Release SHA-256、RSA 独立签名、组件内嵌版本和服务入口；
4. 缓存缺失或校验失败时，才从官方 ShitBot GitHub Release 下载 JAR、`.sha256` 和 `.sig`；
5. 下载完成且全部校验通过后，使用隔离类加载器启动组件。

默认配置：

```yaml
custom-image-templates:
  enabled: false
  directory: "image-templates"
  component:
    version: ""
    download-url: "https://github.com/hutuyee/ShitBot/releases/download/%version%/ShitBotRenderer-%version%.jar"
    maximum-download-bytes: 4194304
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
```

下载器只接受 HTTPS 的官方 ShitBot Release 地址及 GitHub 的 Release 资源重定向。缓存损坏时不会加载损坏 JAR。某个版本的 Release 必须同时包含同版本 `ShitBotRenderer`、checksum 和签名；否则高级模板启动失败，但关闭总开关后仍可使用内置 Java 图片。

## 模板目录

首次成功启动高级组件且模板根目录为空时，会创建并发布一个 `online-status` 示例：

```text
image-templates/
└─ online-status/
   ├─ manifest.yml
   ├─ scene.yml
   ├─ assets/
   ├─ published.yml
   └─ versions/
      └─ 1/
         ├─ manifest.yml
         ├─ scene.yml
         └─ assets/
```

- 根目录的 `manifest.yml`、`scene.yml` 和 `assets/` 是草稿；
- `versions/<编号>/` 是发布时复制出的生产快照；
- `published.yml` 指向当前生产版本并记录快照 SHA-256；
- 正常渲染只读取并复核已发布快照，不读取正在编辑的草稿；
- 回滚只切换生产指针，不覆盖草稿，也不修改历史版本。

不要手工修改 `versions/` 或 `published.yml`。要修改模板，应编辑草稿后重新发布一个新版本。

模板 ID 只能使用小写字母、数字、下划线和连字符，最长 64 个字符。所有文件必须位于插件数据目录内；绝对路径、目录穿越和符号链接越界都会被拒绝。

## manifest.yml

```yaml
schema-version: template-v1
id: player-card
name: Player card
suggested-file-name: player-card.png
providers:
  - id: shitbot
  - id: player-avatar
    player: "${context.player}"
  - id: papi
    player: "${context.player}"
    server: "${context.server}"
    placeholders:
      display_name: "%player_displayname%"
      health: "%player_health%"
```

`providers` 只声明模板确实需要的数据。模板不能动态调用任意 Java 类、网络接口或控制台命令。数据先在平台安全线程或独立数据线程收集，完成后才把普通 Map/List/字符串/数字传给图片线程。

内置提供器：

| ID | 输出位置 | 内容 |
| --- | --- | --- |
| `shitbot` | `${data.shitbot.*}` | 平台名、插件版本、生成时间 |
| `online-players` | `${data.online-players.*}` | 总在线数、子服列表、玩家列表；头像配置开启时还提供头像 URL |
| `player-avatar` | `${data.player-avatar.*}` | 指定玩家名和按 `image.avatar.url-template` 生成的头像 URL |
| `papi` | `${data.papi.*}` | 显式声明的 PlaceholderAPI 值、`values` 映射和逐项 `errors` 映射 |

`player-avatar` 或其他 HTTPS 图片 URL 要真正绘制时，还必须显式开启 `custom-image-templates.remote-images.enabled`。默认只允许读取模板自己的 `assets/`。

## scene.yml

最小场景：

```yaml
schema-version: scene-v1
canvas:
  width: 900
  height: 500
  background: "#172033"
layers:
  - type: text
    x: 40
    y: 32
    width: 820
    text: "Hello ${context.player}"
    font-size: 36
    font-style: bold
    color: "#FFFFFFFF"
```

颜色支持 `#RRGGBB` 和 `#AARRGGBB`。画布还支持 `gradient-start` 与 `gradient-end`。

所有普通图层可使用 `x`、`y`、`width`、`height`、`opacity`、`visible` 和 `when`。主要节点如下：

| 节点 | 常用属性 |
| --- | --- |
| `text` | `text`、`font-family`、`font-size`、`font-style`、`color`、`align`、`line-height`、`maximum-lines` |
| `image` | `source`、`fit: cover/contain/stretch`、`radius`、描边；本地资源写 `assets/name.png` |
| `avatar` | 与 `image` 相同，但默认使用圆形裁剪 |
| `rectangle` | `fill`、`radius`、`stroke-color`、`stroke-width` |
| `circle` | `fill`、`diameter` 或宽高、描边 |
| `line` | `x2`/`y2` 或宽高、`color`、`stroke-width` |
| `progress` | `value`、`maximum`、`background`、`fill`、`radius` |
| `group` | `children`；可用 `clip: true` 按组宽高裁剪 |
| `stack` | `children`、`direction: vertical/horizontal`、`gap` |
| `grid` | `children`、`columns`、`cell-width`、`cell-height`、行列间距 |
| `condition` | `condition`、可选 `equals`、`then`、`else` |
| `loop` | `items`、`as`、`maximum-items`、`children`；普通布局还可使用单项偏移 |

绑定规则：

- `${context.player}` 读取调用方上下文；
- `${data.papi.health}` 读取数据提供器；
- 表达式独占整个值时保留原始类型，因此 `${data.online-players.servers}` 可以直接交给 `loop.items`；
- 表达式嵌入文字时转换为字符串；
- 循环变量直接作为根使用，例如 `as: player` 后读取 `${player.name}`；
- 循环还提供 `${index}`、`${first}` 和 `${last}`。

PAPI 表达式只能写在 manifest 的 `papi.placeholders` 声明中。场景只读取 `${data.papi.<别名>}`，不会扫描任意文本并隐式执行 PlaceholderAPI。

## PlaceholderAPI 与代理后端

Spigot/Paper/Folia 在玩家所属的正确调度线程批量解析声明的占位符。PlaceholderAPI 未安装、玩家不在线、表达式未解析或请求超时时都会返回明确错误。`maximum-queries`、`timeout-ms` 和 `cache-seconds` 控制数量、单次等待和短期缓存。

BungeeCord/Velocity 本身不解析 Bukkit PlaceholderAPI。模板的 `papi.server` 或群命令的 `target-server` 会通过已有的认证代理—后端通道把请求发送到指定 Spigot 后端；协议双方必须使用相同 ShitBot 版本。后端需要安装 PlaceholderAPI，并配置 `backend-transport.listener`。

Nukkit-MOT 不会冒充 Bukkit PlaceholderAPI。`shitbot`、`online-players`、`player-avatar` 和第三方注册的数据提供器可以使用；声明 `papi` 会得到不支持错误。

## 自定义 QQ 图片命令

高级模板本身不会注册命令。在 `commands.yml` 中显式配置每个入口：

```yaml
image-templates:
  commands:
    player-card:
      enabled: true
      aliases:
        - "我的名片"
      template: "player-card"
      player-source: "bound"
      target-server: "survival"
      permission: "shitbot.image.player-card"
      cooldown-seconds: 10
      usage: "%at% 用法：我的名片 [已绑定角色名]"
      failed: "%at% 图片生成失败：%result%"
```

`player-source` 可选：

| 值 | 行为 |
| --- | --- |
| `bound` | 使用发送者 QQ 最新绑定的角色；命令后的其余文字保留在 `${context.arguments}` |
| `argument` | 命令后必须提供精确角色名，并确认该角色属于发送者 QQ |
| `none` | 不读取绑定，`${context.player}` 为空；若配置非空 `permission`，因为没有角色可校验而不会通过 |

可用上下文包括 `title`、`server-name`、`qq`、`group`、`sender`、`player`、`players`、`server`、`arguments`、`command` 和 `platform`。权限请求只执行权限判断，不执行控制台命令。别名冲突时，先匹配内置 OneBot 指令和已有控制台快捷命令，因此应使用唯一别名。

## 可视化编辑器

编辑器默认关闭：

```yaml
custom-image-templates:
  editor:
    enabled: true
    bind-address: "127.0.0.1"
    port: 0
    login-seconds: 60
    maximum-upload-bytes: 2097152
```

重载后，由有 `shitbot.admin` 权限的管理员执行：

```text
/shitbot editor
```

命令返回短时、一次性登录地址。登录后会建立 30 分钟滑动会话。编辑器提供模板列表、新建模板、图层列表、拖动与缩放、5 像素吸附、属性和完整图层 JSON、YAML 源码、数据提供器解析、真实预览、资源上传、发布、版本历史与回滚。

监听地址必须解析为回环地址。需要远程使用时，应由管理员自行配置 HTTPS 反向代理，并保留原始 `Host`；不要直接把编辑器端口暴露到公网。编辑器请求还受会话 Cookie、同源检查、自定义请求头、CSP、上传大小和模板路径限制保护。

## 资源限制

```yaml
custom-image-templates:
  limits:
    maximum-width: 2400
    maximum-height: 2400
    maximum-pixels: 8388608
    maximum-layers: 256
    maximum-loop-items: 200
    maximum-asset-bytes: 2097152
    maximum-template-asset-bytes: 16777216
    render-timeout-ms: 5000
  render:
    threads: 2
    maximum-queued: 16
  remote-images:
    enabled: false
  data:
    maximum-queries: 32
    timeout-ms: 3000
    cache-seconds: 10
```

图层上限同时限制循环展开后的实际节点数。渲染线程和等待队列都是有界的；队列满或超时会快速失败。远程图片仅支持 HTTPS，并检查端口、重定向、内网地址、响应类型、字节数和解码后的像素尺寸。

## 插件 API

第三方插件以 `provided` 方式依赖轻量模块：

```xml
<dependency>
  <groupId>haaa</groupId>
  <artifactId>ShitBotApi</artifactId>
  <version>${shitbot.version}</version>
  <scope>provided</scope>
</dependency>
```

以 Bukkit 为例，可从插件主类取得 API：

```java
Plugin plugin = Bukkit.getPluginManager().getPlugin("ShitBotSpigot");
if (!(plugin instanceof ShitBotApiProvider)) {
    return;
}
ShitBotApi api = ((ShitBotApiProvider) plugin).getShitBotApi();
if (api == null || !api.isCustomImageTemplatesEnabled()) {
    return;
}

Map<String, Object> context = new LinkedHashMap<String, Object>();
context.put("player", player.getName());
context.put("server", "survival");
api.renderImage("player-card", ImageRenderRequest.of(context))
        .thenAccept(result -> {
            byte[] png = result.getBytes();
            String fileName = result.getSuggestedFileName();
            long version = result.getTemplateVersion();
            // 调用插件自行决定发送、缓存或保存。
        });
```

`ImageRenderResult` 包含 PNG 字节、宽、高、内容类型、建议文件名、模板 ID 和模板版本。所有调用都是异步的；不要在完成回调里直接访问要求主线程/区域线程的平台 API。

注册自定义提供器：

```java
api.registerImageDataProvider(new ImageDataProvider() {
    public String getId() {
        return "my-plugin";
    }

    public CompletableFuture<Map<String, Object>> provide(ImageDataRequest request) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("value", "example");
        return CompletableFuture.completedFuture(data);
    }
});
```

模板在 manifest 中声明 `id: my-plugin` 后，从 `${data.my-plugin.value}` 读取。提供器 ID 必须唯一；卸载或重载第三方插件时，应使用同一个实例调用 `unregisterImageDataProvider`。
