# Platform compatibility

[简体中文](../compatibility.md) | **English**

## Platforms and Java

| Module | Runs on | Minimum plugin Java | Main purpose |
| --- | --- | --- | --- |
| ShitBotSpigot | Bukkit servers such as Spigot, Paper, Folia, and CatServer | 8 | Standalone server or Bukkit backend behind a proxy |
| ShitBotBungee | BungeeCord | 8 | Network proxy, OneBot, cross-server forwarding, and backend commands |
| ShitBotVelocity | Velocity | 21 | Network proxy, OneBot, cross-server forwarding, and backend commands |
| ShitBotNukkit | Nukkit-MOT | 17 | Standalone Nukkit-MOT server |

The server software may require a newer Java version. For example, ShitBotSpigot's Java 8 compatibility does not mean modern Paper can run on Java 8.

## Recorded environments

The repository documents adaptation or verification for these environments:

- Spigot 1.8.8.
- Spigot 1.12.2.
- Paper 1.21.
- Paper 1.21.11.
- Paper 26.1.
- Folia 1.21.11.
- CatServer 1.12.2.
- BungeeCord `26.1-R0.1-SNAPSHOT` builds.
- Multiple Velocity versions.
- Nukkit-MOT; the module's documented compile dependency is `1.26.40-R1`.

These records do not guarantee identical behavior for every server fork, plugin combination, or modded hybrid environment. Report the exact server software, version, and complete startup log for compatibility problems.

## Feature differences

| Feature | Spigot standalone | Spigot backend | BungeeCord / Velocity | Nukkit-MOT |
| --- | --- | --- | --- | --- |
| Direct OneBot connection | Yes | No | Yes | Yes |
| Binding check on login | Yes | Yes | At the proxy | Yes |
| Chat forwarding | Yes | Handled by proxy | Yes | Text and URLs |
| Save live inventory snapshots | Yes | Yes | Cannot read live inventories directly | Yes |
| Render inventories from a shared database | Yes | Yes | Yes | Yes |
| Built-in profile card and online time | Yes | Does not record backend sessions | Yes | Yes |
| Proxy/backend command channel | Can act as backend | Listens for proxy requests | Dispatches to backends | Not used |
| PictureBridge media markers | Yes | Depends on proxy message entry | Yes | Not used |
| Advanced Java2D scenes | Yes | Can supply backend data | Yes | Yes, without Bukkit PAPI |
| PlaceholderAPI template data | Resolve locally | Resolve proxy requests | Forward to selected Spigot backend | Not supported |

Backend Spigot instances do not connect to OneBot, avoiding duplicate QQ replies from the proxy and backend.

## Folia

The Spigot descriptor declares `folia-supported: true`. Inventory fields are copied on Bukkit's main thread or the player's region thread on Folia. Database operations, resource scanning, and rendering run asynchronously.

Other plugins that modify inventories, permissions, or logs through methods unsafe for Folia can still affect integrations.

## Optional dependencies

Basic operation does not require these plugins, but they extend permissions or data sources:

| Plugin | Platform | Purpose |
| --- | --- | --- |
| LuckPerms | Spigot, BungeeCord, Velocity, Nukkit-MOT | Offline character permissions |
| Vault | Spigot | Offline permissions when LuckPerms is unavailable |
| Essentials / EssentialsX | Spigot | Preferred source of server TPS data |
| PlaceholderAPI | Spigot | Batched placeholder resolution for advanced templates explicitly declaring `papi` |

Without optional permission plugins, online players still use platform permissions, but offline permission checks are limited.

## OneBot

ShitBot uses OneBot v11 forward WebSocket. The implementation must support:

- Group message events.
- Sending group messages.
- Image message segments.
- Action responses.
- The `Authorization: Bearer` header when using access-token authentication.

Fields for files, video, voice, share cards, and temporary media URLs vary between implementations. ShitBot converts them to in-game labels where possible; if no URL is available, it shows the available summary.

## PictureBridge

Java players can install the separate [PictureBridge](https://github.com/hutuyee/PictureBridge) client mod when using `forwarding.group-to-game.media-mode: "picturebridge"`.

PictureBridge is not bundled in ShitBot's server JAR. Nukkit-MOT does not use this client protocol.
