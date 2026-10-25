# 插件 API 与白名单管理

**简体中文** | [English](en/api.md)

`ShitBotApi` 是 Java 8 的轻量公共模块，不依赖 Bukkit、代理 API、数据库驱动或高级渲染组件。绑定、白名单与原生图片 API 在关闭高级图片模板时也可使用。

## 依赖与获取

Release 会单独提供 `ShitBotApi-<版本>.jar` 及其校验与签名文件，供开发者使用；这个 JAR 不放入服务端的 `plugins/`。也可以从仓库执行 `mvn -pl :ShitBotApi -am install` 安装当前版本的 API 到本机 Maven 仓库。

先将相同版本的 `ShitBotApi` 安装到你的 Maven 仓库，第三方插件使用 `provided` 依赖，不要把 API 或 ShitBotCore 再打包进自己的插件：

```xml
<dependency>
  <groupId>haaa</groupId>
  <artifactId>ShitBotApi</artifactId>
  <version>${shitbot.version}</version>
  <scope>provided</scope>
</dependency>
```

Bukkit 插件在 `plugin.yml` 声明 `depend: [ShitBotSpigot]`，再通过主类获取：

```java
import haaa.shitbot.api.ShitBotApi;
import haaa.shitbot.api.ShitBotApiProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

Plugin plugin = Bukkit.getPluginManager().getPlugin("ShitBotSpigot");
ShitBotApi api = plugin instanceof ShitBotApiProvider
        ? ((ShitBotApiProvider) plugin).getShitBotApi() : null;
if (api == null || !api.isReady()) {
    return; // 数据库与运行实例可能仍在异步启动，稍后重新获取。
}
api.getBindingsByQq("123456789").thenAccept(bindings -> {
    for (haaa.shitbot.api.PlayerBinding binding : bindings) {
        String name = binding.getPlayerName();
        // 使用返回的数据；如要访问玩家或世界，请切回平台要求的线程。
    }
});
```

BungeeCord、Velocity、Nukkit 的主类同样实现 `ShitBotApiProvider`，通过对应平台插件管理器取到实例后转换即可。ShitBot 的 `/shitbot reload` 会替换运行实例，旧 API 的 `isReady()` 变为 `false`，调用方应重新获取 API 并重新注册自己的图片数据提供器。

## 账号接口

除 `isReady()` 外，下列操作均返回 `CompletableFuture`，不会在调用线程执行 SQL。不要在主线程使用 `join()` 或 `get()` 等待。

| 方法 | 返回内容 |
| --- | --- |
| `getBinding(playerName)` | 精确游戏 ID 的 `Optional<PlayerBinding>`，包括无 QQ 白名单 |
| `getBindingsByQq(qqId)` | 该 QQ 的全部角色，按最近更新排序；无效或空 QQ 返回空列表 |
| `getWhitelist(offset, limit)` | 按添加顺序分页的全部条目；offset ≥ 0，limit 为 1–100 |
| `addWhitelist(playerName)` | 添加无 QQ 白名单，只允许登录 |
| `addWhitelist(playerName, qqId)` | 管理员直接添加绑定；QQ 为 null 或空白时仅添加白名单 |
| `bind(playerName, qqId, code)` | 与群内绑定相同的验证码、尝试次数与 QQ 数量限制 |
| `removeWhitelist(playerName)` | 删除指定角色并请求断开其连接，返回被删除的条目 |
| `removeBindingsByQq(qqId)` | 删除该 QQ 的全部绑定并请求断开对应连接，返回删除的列表 |

`PlayerBinding` 包含精确游戏 ID、可选 UUID、可选 QQ，以及毫秒 Unix 时间戳 `createdAt` / `updatedAt`。游戏 ID 区分大小写。列表与记录不可变。

添加白名单不需要验证码，但仍遵守 `binding.maximum-ids-per-qq` 等绑定设置。已存在相同归属时返回 `ALREADY_BOUND_SAME`；遇到其他归属或已有无 QQ 白名单时返回 `PLAYER_ALREADY_BOUND`，不会自动覆盖。修改归属需要先明确删除旧条目，再添加新条目。

`BindingResult.isSuccess()` 对 `SUCCESS` 和 `ALREADY_BOUND_SAME` 返回 true。其余状态包括 `INVALID_INPUT`、`QQ_BINDING_LIMIT_REACHED`、`PLAYER_ALREADY_BOUND`、`INVALID_CODE`、`EXPIRED_OR_MISSING` 和 `TOO_MANY_ATTEMPTS`；`QQ_ALREADY_BOUND` 保留用于兼容。数据库失败、停机或队列满会让 Future 异常完成。删除操作完成表示数据库删除已提交、断开请求已提交给平台，不表示玩家连接已同步关闭。

这些是服务器插件的管理接口。调用插件必须自行校验权限，不能把 `addWhitelist`、`getWhitelist` 等直接作为无权限的玩家或网页入口。

## 管理员命令

四个平台都提供 `/shitbot whitelist`，需要 `shitbot.admin`。`add` 把 QQ 放在游戏 ID 前，便于支持包含空格的基岩版名称；`-` 表示没有 QQ。

```text
/shitbot whitelist add - Steve
/shitbot whitelist add 123456789 Alex
/shitbot whitelist add - Bedrock Player
/shitbot whitelist get Steve
/shitbot whitelist qq 123456789
/shitbot whitelist list 1
/shitbot whitelist remove Steve
/shitbot whitelist remove-qq 123456789
```

无 QQ 白名单储存在现有 `shitbot_bindings` 表中，`qq_id` 为空字符串，不需要给条目伪造 QQ 号，也不改变现有数据库结构。它允许通过 ShitBot 的绑定登录校验；服务端核心自带的白名单等其他登录限制仍独立生效。

无 QQ 条目不会出现在任何 QQ 的角色列表里，不能通过群内背包、需要绑定的快捷命令或个人图片命令访问。管理员仍可用上述命令和 API 管理条目。公开在线列表仍显示在线玩家名。要给无 QQ 玩家补绑，管理员需先移除无 QQ 条目，然后让玩家正常获取验证码绑定，或使用有 QQ 的管理添加命令。

## 图片接口

原生图片与高级模板统一使用 `ImageRenderResult`。以下渲染和保存方法都返回 `CompletableFuture`；PNG 渲染、数据库查询和文件写入不会在调用线程执行。命中缓存时 Future 可以立即完成，回调不保证位于异步线程。

| 方法 | 返回内容与条件 |
| --- | --- |
| `renderOnlineImage()` | `ImageRenderResult`；沿用 `image.renderer`、在线快照与 `image.cache-seconds`，可使用原生图片或配置的高级模板 |
| `renderPlayerProfileImage(playerName)` | 指定精确角色名的原生个人信息图；不要求 QQ 绑定，也不要求启用高级模板 |
| `isInventoryEnabled()` | 同步读取背包功能是否配置为启用 |
| `renderInventoryImage(playerName)` | 管理接口；指定角色的原生背包图，返回 `InventoryImageResult`，不要求 QQ 绑定 |
| `renderInventoryImageForQq(qqId)` | 沿用群内背包命令的绑定角色自动选择规则，返回 `InventoryImageResult` |
| `renderInventoryImageForQq(qqId, playerName)` | 校验指定角色属于该 QQ；角色名为空时自动选择，返回 `InventoryImageResult` |
| `renderImage(templateId, request)` | 指定已发布高级模板的 `ImageRenderResult`；要求 `custom-image-templates.enabled: true` |
| `saveImage(image, target)` | 将已经生成的结果异步保存到 `Path`，返回绝对、规范化后的本地文件路径 |

个人信息与背包的便捷方法始终使用原生布局。要使用可编辑的个人信息、背包场景，分别调用 `renderImage("player-profile", request)`、`renderImage("inventory", request)`，并在请求上下文传入 `player`。高级模板示例、模板列表、数据提供器注册和编辑器登录见[图片渲染与高级模板](image-templates.md#插件-api)。

按角色名查询个人信息和背包是提供给受信任插件的管理能力，调用方负责权限校验。面向 QQ 用户的背包入口应使用 `renderInventoryImageForQq`，QQ 身份应来自已认证的请求。所有背包接口都遵守 `inventory.enabled`、平台采集能力和快照保留期。

### PNG、Base64 与流式输出

默认结果只保存 PNG 与元数据，不自动编码 Base64，也不重新解码像素来获取宽高。

| `ImageRenderResult` 方法 | 含义 |
| --- | --- |
| `getBytes()` | PNG 字节的独立副本；调用方修改数组不会污染缓存 |
| `getByteSize()` | PNG 字节数，不复制图片 |
| `openStream()` | 独立的只读输入流，打开时不复制整个 PNG |
| `writeTo(outputStream)` | 使用最多 8 KiB 的缓冲区写出；不关闭或刷新调用方的输出流 |
| `getBase64()` | 按需生成标准 Base64，保留填充、不插入换行，不带任何前缀 |
| `getDataUri()` | 按需生成 `data:image/png;base64,...`，可用于网页图片来源 |
| `getWidth()` / `getHeight()` | 图片像素尺寸 |
| `getContentType()` / `getSuggestedFileName()` | 内容类型与建议文件名 |
| `getTemplateId()` / `getTemplateVersion()` | 高级模板的真实 ID、发布版本；原生图使用 `builtin:online`、`builtin:profile`、`builtin:inventory`，版本为 `1` |

Base64 是可用的文本传输格式，但编码后的长度为 `4 × ceil(PNG字节数 / 3)`，还会分配字符串。性能优先时，已有 HTTP 服务可以在自己的工作线程设置 `Content-Type: image/png`、`Content-Length: getByteSize()`，然后调用 `writeTo`；只有对方需要 JSON 文本或内联图片时再编码。Base64 不会被结果对象额外缓存，重复调用会重复编码。

`getBytes`、`getBase64`、`getDataUri`、流的读取和 `writeTo` 都是同步辅助方法。大图复制、编码、网络上传应放到调用插件自己的有界执行器；不要因为渲染方法返回 Future，就假设后续回调一定不会占用服务器主线程。例如，`imageConsumerExecutor` 为调用插件负责创建和关闭的有界执行器：

```java
CompletableFuture<String> encoded = api.renderPlayerProfileImage("Steve")
        .thenApplyAsync(ImageRenderResult::getBase64, imageConsumerExecutor);

// 需要浏览器 data URI 时，将 getBase64 换成 getDataUri。
// OneBot 的 base64:// 前缀属于 OneBot 协议，由发送方按需要添加。
```

### 保存图片与取得地址

```java
java.nio.file.Path target = plugin.getDataFolder().toPath()
        .resolve("exports").resolve("online.png");

api.renderOnlineImage()
        .thenCompose(image -> api.saveImage(image, target))
        .thenAccept(path -> {
            java.net.URI localAddress = path.toUri();
            // file: 地址只用于能够访问该服务器文件系统的程序。
            plugin.getLogger().info(localAddress.toString());
        })
        .exceptionally(error -> {
            plugin.getLogger().warning("图片导出失败: " + error);
            return null;
        });
```

`saveImage` 会创建父目录、替换目标文件，在同一目录先写唯一临时文件再移动；文件系统支持时使用原子替换，否则退回普通替换。它保存传入的那一张图片，不再次渲染，也不读取可能已被其他玩家请求覆盖的共享输出文件。在线图与个人信息图原有的 `output-file` 落盘行为仍保留。

返回的 `Path` / `file:` URI 是本地地址，`data:` URI 是内联图片；两者都不是公网 HTTP 图片链接。需要 HTTP URL 时，由调用方通过已有网站、反向代理的静态目录或对象存储发布，并返回对应地址。此 API 不启动新的监听端口，也不自动上传图片。文件的命名、访问权限、覆盖策略和清理周期由调用插件管理。

### 背包结果

```java
api.renderInventoryImageForQq("123456789", "Steve").thenAccept(result -> {
    if (!result.isSuccess()) {
        plugin.getLogger().info("背包查询状态: " + result.getStatus());
        return;
    }
    ImageRenderResult image = result.getImage().get();
    long snapshotTime = result.getCapturedAt();
    boolean capturedNow = result.isLive();
    // 将 image 交给自己的发送流程，或继续使用 api.saveImage。
});
```

| 状态 | 含义 |
| --- | --- |
| `SUCCESS` | 有图片；同时提供实际角色名、快照服务器、毫秒 Unix 时间戳 `capturedAt` 与 `live` |
| `NOT_BOUND` | QQ 没有绑定角色 |
| `PLAYER_NOT_BOUND` | 指定角色不属于该 QQ |
| `NO_SNAPSHOT` | 本次无法取得在线背包，也没有保留期内的离线快照 |
| `DISABLED` | 背包功能关闭 |

非成功状态的 `getImage()` 为空，角色名与服务器名为空字符串，快照时间为 `0`，`live` 为 `false`。无效角色名、无效 QQ、数据库或渲染失败通过 Future 异常返回，不伪装成“没有快照”。在线采集不可用时仍沿用已有服务的离线快照回退。

### 并发与生命周期

- 一个运行实例的图片 API 最多接收 32 个未完成调用，包括等待数据的调用；满额时 Future 以 `RejectedExecutionException` 失败，不在主线程补做渲染。
- 在线图与群内命令共享成品缓存和正在进行的渲染；相同精确角色名的个人信息 API 请求合并正在进行的查询与渲染，不另加会隐藏字段更新的长期成品缓存。
- 相同背包查询合并正在进行的工作，并沿用已有背包 PNG 缓存；共享结果只包装一次 PNG 元数据。高级模板和背包继续受各自配置的渲染线程、队列和资源上限约束；原生个人信息渲染为 1 个工作线程、最多 16 个等待任务。
- 文件导出为 1 个按需启动的工作线程、最多 8 个等待任务；正在写入和排队图片按任务累计的 PNG 字节数最多 32 MiB，单张超过该限制也会拒绝。导出不额外复制完整 PNG，空闲 30 秒后回收工作线程。
- 取消调用方 Future 不会取消其他调用共享的任务，也不会提前释放实际任务仍占用的容量。遇到过载应退避，避免立即无限重试。
- `/shitbot reload` 或停机后，旧 API 的未完成图片和导出 Future 会异常完成；已经开始的文件写入仍可能完成替换。重新获取新 API 后再调用。已拿到的不可变图片结果仍可读取、编码或交给新 API 保存。
