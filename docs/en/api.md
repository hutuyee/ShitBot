# Plugin API and whitelist management

[简体中文](../api.md) | **English**

`ShitBotApi` is a lightweight Java 8 public module. It does not depend on Bukkit, proxy APIs, database drivers, or the advanced renderer. Binding, whitelist, and native image APIs also work when advanced image templates are disabled.

## Dependency and access

Releases include a separate `ShitBotApi-<version>.jar` with checksum and signature files for developers. Do not put this JAR in the server's `plugins/` directory. You can also install the current API into your local Maven repository with `mvn -pl ShitBotApi -am install`.

Install the matching API version into your Maven repository and use a `provided` dependency. Do not bundle the API or ShitBotCore inside your own plugin:

```xml
<dependency>
  <groupId>haaa</groupId>
  <artifactId>ShitBotApi</artifactId>
  <version>${shitbot.version}</version>
  <scope>provided</scope>
</dependency>
```

Bukkit plugins should declare `depend: [ShitBotSpigot]` in `plugin.yml`, then obtain the API from the main class:

```java
import haaa.shitbot.api.ShitBotApi;
import haaa.shitbot.api.ShitBotApiProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

Plugin plugin = Bukkit.getPluginManager().getPlugin("ShitBotSpigot");
ShitBotApi api = plugin instanceof ShitBotApiProvider
        ? ((ShitBotApiProvider) plugin).getShitBotApi() : null;
if (api == null || !api.isReady()) {
    return; // The database/runtime may still be starting asynchronously. Try again later.
}
api.getBindingsByQq("123456789").thenAccept(bindings -> {
    for (haaa.shitbot.api.PlayerBinding binding : bindings) {
        String name = binding.getPlayerName();
        // Use the data; switch to the required platform thread for player/world access.
    }
});
```

The BungeeCord, Velocity, and Nukkit main classes also implement `ShitBotApiProvider`. Retrieve the instance through the platform's plugin manager and cast it. `/shitbot reload` replaces the runtime; the old API's `isReady()` becomes `false`. Obtain the new API and register your image data providers again.

## Account methods

Except for `isReady()`, these operations return `CompletableFuture` and do not execute SQL on the calling thread. Never wait with `join()` or `get()` on the main thread.

| Method | Result |
| --- | --- |
| `getBinding(playerName)` | `Optional<PlayerBinding>` for the exact Minecraft ID, including whitelist entries without QQ |
| `getBindingsByQq(qqId)` | All characters for that QQ, ordered by most recent update; invalid or empty QQ returns an empty list |
| `getWhitelist(offset, limit)` | All entries, paginated in insertion order; offset ≥ 0 and limit 1–100 |
| `addWhitelist(playerName)` | Add a whitelist entry without QQ, allowing login only |
| `addWhitelist(playerName, qqId)` | Add a binding as an administrator; null or blank QQ adds only a whitelist entry |
| `bind(playerName, qqId, code)` | Apply the same verification code, attempt limits, and QQ binding limits as group binding |
| `removeWhitelist(playerName)` | Delete the character's entry and request disconnection; return the removed entry |
| `removeBindingsByQq(qqId)` | Delete all bindings for the QQ and request corresponding disconnections; return the removed list |

`PlayerBinding` contains the exact Minecraft ID, optional UUID and QQ, and millisecond Unix timestamps `createdAt` / `updatedAt`. IDs are case-sensitive. Lists and records are immutable.

Adding a whitelist entry does not require a code but still respects settings such as `binding.maximum-ids-per-qq`. An existing identical owner returns `ALREADY_BOUND_SAME`. A different owner or an existing entry without QQ returns `PLAYER_ALREADY_BOUND`; entries are not overwritten. To change ownership, explicitly remove the old entry before adding a new one.

`BindingResult.isSuccess()` returns true for `SUCCESS` and `ALREADY_BOUND_SAME`. Other statuses include `INVALID_INPUT`, `QQ_BINDING_LIMIT_REACHED`, `PLAYER_ALREADY_BOUND`, `INVALID_CODE`, `EXPIRED_OR_MISSING`, and `TOO_MANY_ATTEMPTS`. `QQ_ALREADY_BOUND` is retained for compatibility. Database errors, shutdown, or a full queue complete the future exceptionally. A completed removal means the database deletion is committed and the disconnection request has been submitted to the platform, not that the connection has synchronously closed.

These are administrative APIs for server plugins. Callers must enforce their own authorization; do not expose `addWhitelist`, `getWhitelist`, or similar methods as unrestricted player or web endpoints.

## Administrator commands

All four platforms provide `/shitbot whitelist`, requiring `shitbot.admin`. `add` places QQ before the Minecraft ID to support Bedrock names containing spaces. `-` means no QQ.

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

Entries without QQ use the existing `shitbot_bindings` table with an empty `qq_id`. No fabricated QQ or schema change is needed. These entries pass ShitBot's binding login check; other login restrictions, including the server's own whitelist, remain independent.

Entries without QQ do not appear in any QQ account's character list. They cannot be accessed through group inventory queries, shortcuts requiring a binding, or personal image commands. Administrators can still manage them through these commands and the API. Public online lists still show online player names. To add QQ ownership later, first remove the entry, then have the player bind with a new code or add a binding through the admin command.

## Image methods

Native images and advanced templates share `ImageRenderResult`. Rendering and saving methods return `CompletableFuture`; PNG rendering, database queries, and file writes run off the calling thread. A cache hit can complete immediately, so callbacks are not guaranteed to run on a worker thread.

| Method | Result and requirements |
| --- | --- |
| `renderOnlineImage()` | `ImageRenderResult`; follows `image.renderer`, online snapshots, and `image.cache-seconds`, using the native image or configured advanced template |
| `renderPlayerProfileImage(playerName)` | Native profile card for an exact player name; requires neither a QQ binding nor advanced templates |
| `isInventoryEnabled()` | Synchronously reports whether inventory is enabled in configuration |
| `renderInventoryImage(playerName)` | Administrative native inventory image for a player; returns `InventoryImageResult` without requiring a QQ binding |
| `renderInventoryImageForQq(qqId)` | Returns `InventoryImageResult` using the QQ command's owned-character selection rules |
| `renderInventoryImageForQq(qqId, playerName)` | Checks that the requested character belongs to the QQ account; blank names use automatic selection |
| `renderImage(templateId, request)` | `ImageRenderResult` from a published advanced template; requires `custom-image-templates.enabled: true` |
| `saveImage(image, target)` | Saves an existing result asynchronously to a `Path`, returning the absolute normalized local file path |

The profile and inventory convenience methods always use native layouts. For editable scenes, call `renderImage("player-profile", request)` or `renderImage("inventory", request)` with `player` in the request context. See [Image rendering and advanced templates](image-templates.md#plugin-api) for advanced rendering, template listings, data providers, and editor login.

Profile and inventory queries by player name are administrative capabilities for trusted plugins. Callers must authorize access. QQ-facing inventory integrations should use `renderInventoryImageForQq` with an authenticated QQ identity. All inventory methods respect `inventory.enabled`, platform capture support, and snapshot retention.

### PNG, Base64, and streaming

Results retain only PNG bytes and metadata by default. They do not eagerly encode Base64 or decode pixels again to determine dimensions.

| `ImageRenderResult` method | Meaning |
| --- | --- |
| `getBytes()` | Independent PNG byte copy; modifying it cannot corrupt the cache |
| `getByteSize()` | PNG byte length without copying the image |
| `openStream()` | Independent read-only input stream; opening it does not copy the entire PNG |
| `writeTo(outputStream)` | Writes with a buffer of at most 8 KiB; does not close or flush the caller's stream |
| `getBase64()` | On-demand standard padded Base64, without line breaks or a prefix |
| `getDataUri()` | On-demand `data:image/png;base64,...`, suitable for a browser image source |
| `getWidth()` / `getHeight()` | Pixel dimensions |
| `getContentType()` / `getSuggestedFileName()` | Content type and suggested filename |
| `getTemplateId()` / `getTemplateVersion()` | Real advanced template ID and published version; native IDs are `builtin:online`, `builtin:profile`, and `builtin:inventory`, with version `1` |

Base64 is a supported text representation, but its length is `4 × ceil(PNG byte length / 3)` and it allocates a string. For efficient HTTP integration, use your existing HTTP server's worker thread to set `Content-Type: image/png` and `Content-Length: getByteSize()`, then call `writeTo`. Encode only when the consumer needs JSON text or an inline image. Results do not retain a Base64 cache; repeated calls encode again.

`getBytes`, `getBase64`, `getDataUri`, stream reads, and `writeTo` are synchronous helpers. Use your plugin's own bounded executor for large copies, encoding, and uploads. A Future-returning render method does not guarantee that subsequent callbacks run off the server thread. In this example, the calling plugin creates and closes the bounded `imageConsumerExecutor`:

```java
CompletableFuture<String> encoded = api.renderPlayerProfileImage("Steve")
        .thenApplyAsync(ImageRenderResult::getBase64, imageConsumerExecutor);

// Use getDataUri instead for a browser data URI.
// Add OneBot's protocol-specific base64:// prefix at the sending integration if required.
```

### Saving an image and obtaining an address

```java
java.nio.file.Path target = plugin.getDataFolder().toPath()
        .resolve("exports").resolve("online.png");

api.renderOnlineImage()
        .thenCompose(image -> api.saveImage(image, target))
        .thenAccept(path -> {
            java.net.URI localAddress = path.toUri();
            // A file: URI is usable only by programs with access to this server's filesystem.
            plugin.getLogger().info(localAddress.toString());
        })
        .exceptionally(error -> {
            plugin.getLogger().warning("Image export failed: " + error);
            return null;
        });
```

`saveImage` creates parent directories and replaces the target file. It writes a unique temporary file in the same directory, then uses an atomic replacement when supported or a regular replacement otherwise. It saves the exact supplied result without rendering again or reading a shared latest-image file that another player's request may have overwritten. Existing `output-file` writes for online and profile images remain in place.

A `Path` / `file:` URI is a local address; a `data:` URI contains inline image data. Neither is a public HTTP image URL. To return an HTTP URL, publish the image through an existing website, a static directory served by a reverse proxy, or object storage. This API opens no additional listener and uploads nothing automatically. The calling plugin controls filenames, access permissions, replacement policy, and cleanup.

### Inventory outcomes

```java
api.renderInventoryImageForQq("123456789", "Steve").thenAccept(result -> {
    if (!result.isSuccess()) {
        plugin.getLogger().info("Inventory query status: " + result.getStatus());
        return;
    }
    ImageRenderResult image = result.getImage().get();
    long snapshotTime = result.getCapturedAt();
    boolean capturedNow = result.isLive();
    // Pass image to your sending integration, or continue with api.saveImage.
});
```

| Status | Meaning |
| --- | --- |
| `SUCCESS` | Includes an image, actual player name, snapshot server, Unix-millisecond `capturedAt`, and `live` flag |
| `NOT_BOUND` | The QQ account owns no characters |
| `PLAYER_NOT_BOUND` | The requested character does not belong to this QQ account |
| `NO_SNAPSHOT` | No live capture or retained offline snapshot is available |
| `DISABLED` | Inventory is disabled |

Unsuccessful outcomes have an empty `getImage()`, empty player/server strings, timestamp `0`, and `live: false`. Invalid player names or QQ numbers and database/render failures complete the Future exceptionally instead of masquerading as missing snapshots. Unavailable live capture retains the existing offline-snapshot fallback.

### Concurrency and lifecycle

- Each runtime admits at most 32 unfinished image API calls, including calls awaiting data. Excess calls fail with `RejectedExecutionException`; no fallback rendering runs on the main thread.
- Online images share the QQ command's result cache and current render. Concurrent profile API calls for the same exact name share their query and render, without adding a long-lived result cache that would hide field updates.
- Identical inventory queries share in-flight work and retain existing PNG caching; shared results wrap PNG metadata once. Advanced templates and inventory still use their configured render threads, queues, and resource limits. Native profile rendering uses one worker with at most 16 waiting tasks.
- File export uses one on-demand worker and at most eight waiting tasks. The total accounted PNG bytes being written or queued are limited to 32 MiB; a single image exceeding that limit is also rejected. Export does not copy the full PNG. The worker exits after 30 idle seconds.
- Cancelling a caller's Future neither cancels work shared by other calls nor releases capacity before the underlying operation finishes. Back off on overload instead of retrying indefinitely without delay.
- Reloading or stopping completes old-API pending image/export Futures exceptionally. A file write that has already started may still finish replacing its target. Reacquire the API before making new calls. Previously obtained immutable results remain readable and can be encoded or saved through the new API.
