# 插件 API 与白名单管理

`ShitBotApi` 是 Java 8 的轻量公共模块，不依赖 Bukkit、代理 API、数据库驱动或高级渲染组件。绑定与白名单 API 在关闭高级图片模板时也可使用。

## 依赖与获取

Release 会单独提供 `ShitBotApi-<版本>.jar` 及其校验与签名文件，供开发者使用；这个 JAR 不放入服务端的 `plugins/`。也可以从仓库执行 `mvn -pl ShitBotApi install` 安装当前版本的 API 到本机 Maven 仓库。

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

图片渲染、模板列表、数据提供器注册和编辑器登录仍通过同一个 API 提供。`renderImage` 返回 PNG 字节、宽高、内容类型、建议文件名、模板 ID 和版本；示例与数据提供器约定见[图片渲染与高级模板](image-templates.md#插件-api)。
