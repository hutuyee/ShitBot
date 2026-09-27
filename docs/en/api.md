# Plugin API and whitelist management

[简体中文](../api.md) | **English**

`ShitBotApi` is a lightweight Java 8 public module. It does not depend on Bukkit, proxy APIs, database drivers, or the advanced renderer. Binding and whitelist APIs also work when advanced image templates are disabled.

## Dependency and access

Releases include a separate `ShitBotApi-<version>.jar` with checksum and signature files for developers. Do not put this JAR in the server's `plugins/` directory. You can also install the current API into your local Maven repository with `mvn -pl ShitBotApi install`.

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

The same API exposes rendering, template listings, provider registration, and editor login. `renderImage` returns PNG bytes, dimensions, content type, suggested filename, template ID, and version. See [Image rendering and advanced templates](image-templates.md#plugin-api) for examples and provider contracts.
