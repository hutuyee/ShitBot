# Installation and deployment

[简体中文](../installation.md) | **English**

## Requirements

ShitBot needs a QQ bot implementation that supports OneBot v11 forward WebSocket. The plugin initiates the connection; it does not accept reverse WebSocket connections.

Use [LuckyLilliaBot](https://github.com/LLOneBot/LuckyLilliaBot) or another compatible implementation. Prepare:

- A OneBot forward WebSocket address.
- The OneBot access token, if authentication is enabled.
- The QQ group IDs allowed to use the bot.
- A ShitBot JAR matching your platform.

## Choose a platform JAR

Download from [GitHub Releases](https://github.com/hutuyee/ShitBot/releases):

| Platform | JAR | Minimum plugin runtime Java |
| --- | --- | --- |
| Bukkit servers such as Spigot, Paper, Folia, and CatServer | `ShitBotSpigot-*.jar` | Java 8+ |
| BungeeCord | `ShitBotBungee-*.jar` | Java 8+ |
| Velocity | `ShitBotVelocity-*.jar` | Java 21+ |
| Nukkit-MOT | `ShitBotNukkit-*.jar` | Java 17+ |

These are ShitBot's bytecode requirements. Your server software may require a newer Java version, which must also be satisfied. If a release has no JAR for your platform, that build was not published. Do not substitute a JAR for another platform.

## First startup

1. Stop the server or proxy.
2. Place the matching JAR in that instance's `plugins/` directory.
3. Start the instance and wait for ShitBot to create its data directory.
4. Stop the instance.
5. Open the generated `config.yml`, `commands.yml`, `lang/` files, and `templates/default.yml`.
6. Complete the minimal configuration below and start again.

Do not edit default configuration inside the JAR. When later versions add settings, back up your configuration and compare it with newly generated defaults.

## Minimal OneBot configuration

Edit `config.yml`:

```yaml
language: "en_US"

onebot:
  enabled: true
  websocket-url: "ws://127.0.0.1:3001"
  access-token: ""
  allow-all-groups: false
  allowed-group-ids:
    - 123456789
```

`language: "en_US"` enables the English messages and aliases used in this guide. Keep `zh_CN` to use Chinese.

- On the same machine, use `ws://127.0.0.1:<port>`.
- Between hosts, use `wss://` and ensure Java trusts the server certificate.
- `access-token` must match OneBot's configuration.
- If `allowed-group-ids` is empty and `allow-all-groups: false`, all group messages are rejected.
- `allow-all-groups: true` is not recommended for production.

After startup, run:

```text
/shitbot status
```

Confirm that the database and OneBot connection are healthy before enabling forwarding, binding, images, or shortcuts.

## A single Bukkit server

Install only `ShitBotSpigot-*.jar` and keep:

```yaml
deployment:
  role: "standalone"
```

The Spigot plugin connects directly to OneBot. A single instance can use the default SQLite database or MySQL. No `backend-transport` configuration is needed.

## A BungeeCord or Velocity network

For group chat forwarding, QQ binding, and proxy features, install `ShitBotBungee-*.jar` or `ShitBotVelocity-*.jar` on the proxy.

To query a backend's TPS, run backend shortcuts from QQ, or save inventory snapshots on backends:

1. Install the matching edition on the proxy.
2. Install the Spigot edition on each target Bukkit backend.
3. Set each backend's `deployment.role` to `backend`.
4. Connect the proxy and every backend to the same MySQL database.
5. Configure the authenticated command channel in `commands.yml`.

See [Proxies and backend servers](proxy-backend.md) for the full procedure.

## A single Nukkit-MOT server

Place `ShitBotNukkit-*.jar` in Nukkit-MOT's `plugins/` directory. Nukkit-MOT runs as a standalone platform:

- It does not use `deployment.role`.
- It does not use the proxy/backend command channel.
- TPS queries and QQ shortcuts always run locally.
- It supports SQLite and MySQL.
- It does not use the PictureBridge client protocol; media labels retain the original URL.

## Enable chat forwarding

Both directions are disabled by default. Enable them independently in `config.yml`:

```yaml
forwarding:
  game-to-group:
    enabled: true
    require-prefix: true
    prefix: "#qq "
  group-to-game:
    enabled: true
    require-prefix: true
    prefix: "#mc "
    media-mode: "browser"
```

Default usage:

```text
Minecraft: #qq message to the QQ group
QQ group:  #mc message to the game
```

Run `/shitbot reload` after editing. See [Configuration](configuration.md) for more options.

## After installation

Suggested checks:

1. `/shitbot status` reports an available database.
2. OneBot is connected.
3. The bot's group is in `allowed-group-ids`.
4. Sending `server status` with `en_US` (or `服务器状态` with `zh_CN`) gets a reply.
5. After enabling forwarding, both `#qq` and `#mc` work as intended.
6. In a proxy/backend deployment, `TPS <backend-name>` reaches the selected backend.

See [Troubleshooting](troubleshooting.md) if something goes wrong.
