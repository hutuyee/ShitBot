# 图片渲染与高级模板

ShitBot 提供两条互不混装的图片路径。默认模式面向普通服务器，高级模式面向需要自由布局、真实数据预览和插件 API 的服务器。

## 两种模式

| 模式 | 配置 | 运行内容 | 网络下载 | 适用场景 |
| --- | --- | --- | --- | --- |
| 内置 Java 图片 | `image.renderer: "java"` | 平台插件内已有的 Java2D 在线图；外观来自 `templates/*.yml` | 不下载高级渲染组件 | 希望占用低、配置简单的服务器 |
| 高级场景模板 | `custom-image-templates.enabled: true` | 独立的 `ShitBotRenderer` 组件、`image-templates/` 场景、数据提供器和可选编辑器 | 正常模式下本地无有效缓存时下载；debug 模式只加载本地 JAR | 需要自由图层、条件/循环、PAPI 或第三方插件调用的服务器 |

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

新安装使用低饱和的深灰蓝与薄荷绿主题，在线图和背包图采用统一的渐变卡片、柔和阴影、细边框和状态色。在线图使用等宽玩家卡片，按名字实际宽度和 `image.players-per-row` 上限决定列数；标题、在线总人数、子服人数和时间分开排版。头像使用最近邻缩放，保留 Minecraft 像素边缘。

背包图把主背包、快捷栏和装备区分开，快捷栏显示 1–9 编号；数量使用独立底标，附魔物品带淡色高亮，耐久条采用圆角。`inventory.layout.hotbar-gap` 控制快捷栏前的额外间距。底部摘要和数据时间放不下一行时会分成两行，长名字按可用空间省略。

上述布局和绘制变化同样用于已有主题。已有 `templates/default.yml` 的配色和自定义值会保留；升级后希望完整采用新默认主题时，可先备份原文件，再使用新版本的同名资源，并执行 `/shitbot reload`。

每次启动和 `/shitbot reload`，在线图与背包图都会在图片线程预生成静态底稿。后续渲染复制底稿再填入动态数据：在线人数、头像、名称、物品、耐久和时间不会写回底稿，因此玩家离开或物品消失时不会留下旧内容。

在线图按子服名称、面板高度和玩家卡片布局缓存底稿；人数或名称宽度改变布局时生成新的底稿。背包图复用固定格子和装备标签。每种原生图片的底稿缓存最多保留 4 项、合计 8,388,608 像素，采用内存缓存；大于此限的图片仍可渲染，但底稿不常驻缓存。重载与重启创建新缓存，自动使用最新主题、语言和尺寸。现有成品 PNG 缓存仍按 `image.cache-seconds` 与背包渲染缓存配置生效。

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
2. `debug: true` 时，只加载插件数据目录下的 `components/image-renderer/<版本>/ShitBotRenderer-<版本>.jar`，跳过 checksum 和签名校验；文件缺失或无效时直接报错，不会下载；
3. `debug: false` 时，检查 `components/image-renderer/<版本>/` 下的缓存；
4. 正常模式同时校验 JAR 大小、Release SHA-256、RSA 独立签名、组件内嵌版本和服务入口；
5. 缓存缺失或校验失败时，才从官方 ShitBot GitHub Release 下载 JAR、`.sha256` 和 `.sig`；
6. 下载完成且全部校验通过后，使用隔离类加载器启动组件。

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

正常模式下，下载器只接受 HTTPS 的官方 ShitBot Release 地址及 GitHub 的 Release 资源重定向。缓存损坏时不会加载损坏 JAR。某个版本的 Release 必须同时包含同版本 `ShitBotRenderer`、checksum 和签名；否则高级模板启动失败，但关闭总开关后仍可使用内置 Java 图片。

调试模式的本地 JAR 仍会检查大小，并且必须包含 `META-INF/services/haaa.shitbot.api.spi.ImageTemplateEngineFactory` 和匹配版本的 `META-INF/shitbot-renderer.version`，但不读取旁边的 `.sha256` 和 `.sig` 文件，也不进行公钥校验。缺少 JAR 时，错误信息会给出需要放置文件的完整路径。

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

默认 `online-status` 使用 1200 × 960 的白灰色卡片：背景为纯浅灰 `#F3F4F6`，主卡片为不透明白色，标题与玩家名称使用深灰 `#20262E`，提示和页脚使用 `#59636E`。少量灰蓝色 `#355874` 配合浅蓝灰 `#EDF2F6`，只用于突出总在线人数；不再使用粉紫渐变和大面积装饰色块。顶部展示标题、配置的服务器名和总在线人数。Bukkit/Spigot、Nukkit 等单服平台直接排列玩家名片，不显示 `CraftBukkit` 等实现名称或子服分组框；BungeeCord、Velocity 从代理自身的在线快照取得玩家所在的服务器 ID，再按两列排列子服面板，即使只有一个子服也保留 ID。每位玩家显示圆角方形的 Minecraft 像素头像与名字，页脚只展示 ShitBot 标识和数据生成时间。头像直接读取 `online-players` 提供的 `${player.avatar}`，沿用 `image.avatar.url-template`；`image.avatar.enabled: false` 时不请求玩家头像。

默认配色参考 WCAG 2.2 的[文字对比度要求](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)：普通文字至少为 4.5:1，大字至少为 3:1。按模板指定的 sRGB 色值计算，次要文字在白底上约为 6.11:1，子服人数文字在浅灰标签上约为 5.45:1，总在线人数及其说明约为 6.66:1。人数指示点在白底上的对比度高于[有意义图形的 3:1 要求](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)，并始终配有人数文字，避免[仅用颜色传递信息](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html)。白灰大面积、强调色小面积是本模板的视觉选择，WCAG 不规定颜色面积比例；这些色值计算也不代表完整的无障碍评估。

单服按四列直接展示最多 40 位玩家；代理最多展示 4 个子服，每服最多展示 8 位玩家。人数统计始终使用完整在线数据，超出展示范围时会显示对应提示。总在线人数为 0 时显示整页空状态，代理数据中的空子服也有独立提示。两种布局沿用默认图层和画布限制，无需提高资源上限。

升级不会覆盖已经生成或修改过的模板。编辑器中新建模板会使用新版默认布局；要更新原有 `online-status`，可把发行源码中 `ShitBotRenderer/src/main/resources/defaults/online-status/scene.yml` 的内容放入该模板的 `scene.yml` 草稿，再通过编辑器保存、预览并发布。

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
| `online-players` | `${data.online-players.*}` | `total` 总在线数、`players` 平铺玩家列表、`servers` 分组列表及 `group-by-server` 代理分组标记；头像配置开启时还提供头像 URL |
| `player-avatar` | `${data.player-avatar.*}` | 指定玩家的 `player`、`url`，以及头像服务地址模板 `url-template`；`template-only: true` 仅提供地址模板，供头像图层分别绑定玩家 |
| `papi` | `${data.papi.*}` | 显式声明的 PlaceholderAPI 值、`values` 映射和逐项 `errors` 映射 |

`online-players.group-by-server` 由运行平台决定：BungeeCord、Velocity 为 `true`，单服平台为 `false`。代理分组的 `servers[].id` 是代理提供的服务器 ID，`servers[].name` 保留原有名称字段；各分组仍提供 `players` 和 `count`。手写单服模板可直接循环 `${data.online-players.players}`，不必显示分组标题。

`custom-image-templates.remote-images.enabled` 现在默认为 `true`，允许 `player-avatar` 和其他 HTTPS 图片 URL，同时仍支持模板自己的 `assets/`。旧配置中显式写出的 `false` 会继续生效；需要网络头像时，将它改为 `true` 后重载。HTTPS、地址、响应大小和像素限制仍然有效。

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
| `avatar` | `player`（玩家名或变量）、`source`（直接图片来源）、`shape: square/circle`、`pixelated`、宽高、圆角和描边；未指定形状的旧头像图层仍使用圆形 |
| `rectangle` | `fill`、`radius`、`stroke-color`、`stroke-width` |
| `circle` | `fill`、`diameter` 或宽高、描边 |
| `line` | `x2`/`y2` 或宽高、`color`、`stroke-width` |
| `progress` | `value`、`maximum`、`background`、`fill`、`radius` |
| `group` | `children`；可用 `clip: true` 按组宽高裁剪 |
| `stack` | `children`、`direction: vertical/horizontal`、`gap` |
| `grid` | `children`、`columns`、`cell-width`、`cell-height`、行列间距 |
| `condition` | `condition`、可选 `equals`、`then`、`else` |
| `loop` | `items`、`as`、`maximum-items`、`children`；普通布局还可使用单项偏移 |

### 放置玩家头像

在编辑器顶部选择「玩家头像」并添加图层，然后在右侧设置头像来源：

- **指定玩家名 / 自定义变量**：填写玩家名，或 `${player.name}` 等变量。同一模板中的不同头像可以分别使用不同玩家。画布根级新增头像默认填入示例名 `Steve`，可以直接改成自己的玩家名。
- **当前玩家**：使用 `${context.player}`，适合玩家资料卡或绑定玩家的自定义命令。在「预览数据」的 `context` 中填写 `player` 作为预览玩家；实际渲染由调用方提供，没有玩家值时不绘制该头像。
- **所在循环中的玩家**：将头像添加到玩家循环或其内部容器中时，会自动绑定该循环变量的 `name`，例如 `${player.name}`；支持按 `server.players` 遍历在线玩家。
- **图片地址 / 头像图片变量**：直接填写 `assets/head.png`、HTTPS 图片地址或 `${player.avatar}`。这也兼容已有的头像图片来源写法。

头像可以拖动、缩放，支持方形、圆角方形和圆形。新头像默认使用圆角方形与清晰像素缩放，保留 Minecraft 皮肤的像素边缘；需要时可以切换为平滑缩放。画布显示头像位置及玩家标记，真实头像通过「预览」查看。

网络头像使用 `image.avatar.url-template` 配置的服务。新配置已默认开启远程图片；如果旧配置中关闭了此项，需要改为下面的值后重载：

```yaml
custom-image-templates:
  remote-images:
    enabled: true
```

编辑器添加按玩家名绑定的头像时，会自动补充 `player-avatar` 数据声明，并将图层和声明一起纳入撤销。已有且指定了 `player` 的 `player-avatar` 声明会原样保留；没有选项的简写会自动改成仅提供地址模板的声明。手写模板时，在 `manifest.yml` 的 `providers` 中添加：

```yaml
providers:
  - id: player-avatar
    template-only: true
```

随后在 `scene.yml` 的 `layers` 中放置头像：

```yaml
layers:
  - type: avatar
    name: 玩家头像
    player: Steve  # 可改成 ${context.player} 或玩家循环中的 ${player.name}
    x: 40
    y: 40
    width: 64
    height: 64
    shape: square  # circle 为圆形；square 配合 radius 为圆角方形
    radius: 8
    pixelated: true
```

`player` 模式会根据每个头像图层当前绑定的玩家生成地址，仍通过渲染器已有的图片加载与缓存处理。显式写出 `source` 或兼容字段 `avatar` 时优先使用该图片来源，避免与 `player` 混用。旧的 `player-avatar` 声明仍可使用 `player: "${context.player}"`，并通过 `${data.player-avatar.url}` 读取单个玩家的头像地址。

### 变量绑定

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

命令返回短时、一次性登录地址，`login-seconds` 只控制该地址的登录有效期。登录后建立可反复使用的会话，保存草稿、预览和发布不会使会话失效。会话空闲期限为 24 小时，每次请求都会续期；页面打开期间每 5 分钟自动续期，返回标签页时也会立即续期，因此长时间编辑画布或 YAML 无需依靠保存来维持登录。自动续期不会保存或改动草稿。

编辑器采用中文深色界面：左侧查找模板和图层，中间编辑画布，右侧调整设计属性；YAML、预览数据与渲染预览各有独立的编辑区域。

在画布或图层列表中选中元素后，可以直接按 `Delete` 或 `Backspace` 删除，包括分组、堆叠、网格等布局及其子图层。删除立即生效，可用 `Ctrl+Z` 恢复。输入框内的删除、全选、复制和撤销保持正常的文字编辑行为，中文输入法组词时也不会触发图层快捷键。

| 操作 | 快捷键 |
| --- | --- |
| 保存草稿 | `Ctrl+S` |
| 删除选中图层或布局 | `Delete` / `Backspace` |
| 撤销 / 重做 | `Ctrl+Z` / `Ctrl+Shift+Z` 或 `Ctrl+Y` |
| 复制所选图层 | `Ctrl+D` |
| 加选或取消加选 | `Shift`、`Ctrl` 或 `⌘` + 点击 |
| 全选图层 | `Ctrl+A` |
| 移动 1 像素 / 10 像素 | 方向键 / `Shift` + 方向键 |
| 上移 / 下移一层 | `Ctrl+]` / `Ctrl+[` |
| 平移画布 | 按住空格拖动，或中键拖动 |
| 缩放画布 | `Ctrl` + 滚轮，或画布右下角的缩放控件 |
| 适应画布 | `Ctrl+0` |
| 保持比例调整图层大小 | `Shift` + 拖动右下角 |
| 临时关闭 5 像素吸附 | `Alt` + 拖动 |
| 取消选择 / 取消当前拖动 | `Esc` |
| 打开快捷键说明 | `?`，或右上角的问号 |

macOS 使用 `⌘` 代替 `Ctrl`。编辑器保留当前模板最近 100 步编辑历史；一次拖动对应一次撤销，保存草稿后仍可撤销。切换模板或刷新页面后会重新开始记录。

属性面板按位置与尺寸、文字、外观、布局和显示条件分组。没有选中图层时可以编辑画布尺寸、背景和渐变；颜色选择器兼容模板的 `#AARRGGBB` 格式，并保留原有透明度。单个图层可以相对画布对齐，多个图层可以彼此对齐。选中布局容器后，在顶部的添加位置中选择「选中容器内」「条件成立时」或「条件不成立时」，即可直接添加子图层。位置或尺寸使用变量绑定时，拖动和快捷微调会提示先改为固定数值。

切换模板前会保存当前修改；进入 YAML 视图前会保存并同步设计修改，离开 YAML 视图前会保存并解析源码。保存失败或源码有误时会保留当前视图和修改。页面顶部显示草稿保存状态，关闭或刷新有未保存修改的页面时会提醒。保存草稿与发布模板分开进行，只有点击「发布模板」才会生成新的发布版本。

布局视图用于编辑结构和位置，条件分支及循环只展示结构示例。实际数据、图片和服务端字体效果通过「预览」查看，预览结果可直接下载为 PNG。编辑器还提供图片资源上传、完整图层 JSON、数据提供器解析、发布历史和版本回滚；回滚切换正在使用的发布版本，保留当前草稿。

Java 版玩家在 Spigot/Paper/Folia、BungeeCord 和 Velocity 的聊天框中会收到「打开编辑器」和「复制链接到输入框」按钮。前者交给客户端在浏览器打开，后者把完整地址填入聊天输入框，按 `Ctrl+A`、`Ctrl+C` 即可复制，无需发送聊天消息。Nukkit-MOT 玩家会收到预填完整地址的表单，可以选中地址复制到浏览器。控制台仍输出完整地址。

打开时必须保留完整的 `/login?token=…`，并允许站点 Cookie。登录后可以刷新页面，也可以在同一浏览器里再次点击已使用的链接进入当前会话。不同编辑器端口使用独立 Cookie；浏览器已有其他本地服务的 Cookie 不会影响登录。连续 24 小时未成功续期、浏览器丢失 Cookie 或编辑器服务重启后需要重新登录。未登录或会话失效时，页面会显示重新登录的操作说明；编辑过程中失效可以在新标签页登录，再回到原标签页继续保存草稿。

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
    enabled: true
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
