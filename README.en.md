# ShitBot

[简体中文](README.md) | **English**

A OneBot v11 plugin that connects Minecraft servers to QQ groups. It provides account binding, chat forwarding, online status images, inventory queries, TPS queries, controlled QQ command shortcuts, and an optional advanced image template system.

Supports Spigot/Paper/Folia, BungeeCord, Velocity, and Nukkit-MOT. Use [LuckyLilliaBot](https://github.com/LLOneBot/LuckyLilliaBot) or another implementation compatible with OneBot v11 forward WebSocket.

> [!WARNING]
> Please do not ask for ShitBot support in LLBot groups.
>
> This plugin was developed with AI assistance. Please report any defects.

## Downloads

Download the JAR for your platform from [GitHub Releases](https://github.com/hutuyee/ShitBot/releases):

| Platform | Download | Directory | Java |
| --- | --- | --- | --- |
| Bukkit servers such as Spigot, Paper, Folia, and CatServer | `ShitBotSpigot-*.jar` | `plugins/` | 8+ |
| BungeeCord | `ShitBotBungee-*.jar` | `plugins/` | 8+ |
| Velocity | `ShitBotVelocity-*.jar` | `plugins/` | 21+ |
| Nukkit-MOT | `ShitBotNukkit-*.jar` | `plugins/` | 17+ |

Install only the JAR that matches your platform. Do not place multiple platform editions in one instance. If a release does not include a JAR for your platform, that build was not published; another platform's JAR is not a substitute. The Java versions above are the plugin's bytecode requirements. Your server software may require a newer Java version.

The default image mode does not require `ShitBotRenderer`. Only after you enable `custom-image-templates.enabled` will the plugin download, verify, and cache this optional component from the official release matching its version. Do not place it in `plugins/` manually.

## Installation

1. Stop the server or proxy.
2. Place the matching platform JAR in `plugins/`.
3. Start once to generate `config.yml`, `commands.yml`, the `lang/` files, and `templates/default.yml`.
4. Stop the instance and edit the configuration.
5. Start again, then run `/shitbot status` to check the database and OneBot connection.

Do not hot-load or hot-unload ShitBot with a plugin manager. Use `/shitbot reload` after editing configuration, language files, or image templates. Restart the server or proxy normally after upgrading the JAR.

## Minimal configuration

Set the OneBot address, token, and allowed QQ groups in the plugin data directory's `config.yml`:

```yaml
onebot:
  enabled: true
  websocket-url: "ws://127.0.0.1:3001"
  access-token: ""
  allow-all-groups: false
  allowed-group-ids:
    - 123456789
```

ShitBot initiates a OneBot v11 forward WebSocket connection. Use `wss://` for connections between hosts.

The top-level `language` setting selects the plugin language. Both `zh_CN` and `en_US` are included. For English messages and command aliases, set:

```yaml
language: "en_US"
```

To add another language, copy `lang/zh_CN.yml` or `lang/en_US.yml`, translate and rename it, then use its filename without `.yml` as the language. The QQ command examples in this English guide assume `en_US`; the default Chinese aliases remain available when using `zh_CN`.

To enable chat forwarding:

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

When upgrading from `config-version: 1`, the first load automatically migrates old `messages`, notice text, built-in command aliases/usage, and image titles from `config.yml` to `lang/zh_CN.yml`. The old configuration is not rewritten. After reviewing the migration, you can remove obsolete text entries yourself.

The appearance of online player lists and inventory images is controlled by `templates/*.yml`. Copy `templates/default.yml` to a new file, such as `ocean.yml`, adjust its layout, font sizes, corner radii, and colors, then select it in `config.yml`:

```yaml
image:
  template: "ocean"
inventory:
  template: "ocean"
```

Omit `.yml` from template names. Missing fields fall back to `default.yml`.

Enable the separate `image-templates/` system when you need freely positioned layers, conditions/loops, PAPI, a visual editor, or a rendering API for other plugins. See [Image rendering and advanced templates](docs/en/image-templates.md) for the two modes and component download rules.

Apply your changes with:

```text
/shitbot reload
```

## Deployment options

| Scenario | Installation | Database |
| --- | --- | --- |
| One Bukkit server | Spigot edition only, with `deployment.role: "standalone"` | SQLite or MySQL |
| BungeeCord/Velocity network | Matching edition on the proxy | MySQL recommended |
| QQ commands received by a proxy and executed on backends | Matching proxy edition plus Spigot edition on each target backend with `deployment.role: "backend"` | Shared MySQL required |
| One Nukkit-MOT server | Nukkit edition only | SQLite or MySQL |

Proxy/backend deployments also need `backend-transport` in `commands.yml`. Never let multiple plugin instances use the same SQLite file.

## Common commands

| Command | Purpose | Permission |
| --- | --- | --- |
| `/shitbot status` | Show database, OneBot, and runtime status | None |
| `/shitbot reload` | Reload configuration, language files, image templates, and the runtime | `shitbot.admin` |
| `/shitbot update` | Download and verify an update for this platform; a manual restart is required after replacement | `shitbot.admin` |
| `/shitbot image` | Generate an online status image | `shitbot.admin` |
| `/shitbot editor` | Create a one-time login URL for the advanced template editor | `shitbot.admin` |
| `/shitbot whitelist` | Add, remove, or query bindings and whitelist entries without QQ | `shitbot.admin` |
| `/shitbot migrate easybot [EasyBot.db]` | Import EasyBot bindings | `shitbot.admin` |

On Spigot and Nukkit-MOT, only OPs have `shitbot.admin` by default. BungeeCord and Velocity also check this permission.

## Using QQ groups

Examples with `language: "en_US"`:

| Where | Example | Purpose |
| --- | --- | --- |
| QQ group | `bind Steve ABC123` | Bind a Minecraft account |
| QQ group | `server status` | Get an online status image |
| QQ group | `inventory` or `inventory Steve` | Query the inventory of your own bound character |
| QQ group | `TPS` | Query server TPS |
| QQ group | `lp editor` | Run the configured shortcut |
| Minecraft | `#qq message` | Forward a game message to QQ |
| QQ group | `#mc message` | Forward a group message to the game |

Edit user-facing aliases and text in the selected `lang/*.yml`, feature switches in `config.yml`, and permissions, target backends, and shortcut execution in `commands.yml`.

## Documentation

The [online documentation center](https://hutuyee.github.io/docs/en/) provides navigation and search for ShitBot, BiliMusicBridge, AllMusic QQMusic, and Kugou. The [ShitBot online manual](https://hutuyee.github.io/docs/en/shitbot/) is synchronized with this repository.

- [Installation and deployment](docs/en/installation.md)
- [Configuration](docs/en/configuration.md)
- [Commands and permissions](docs/en/commands.md)
- [Proxies and backend servers](docs/en/proxy-backend.md)
- [Databases and migration](docs/en/database.md)
- [Inventory queries and textures](docs/en/inventory.md)
- [Image rendering and advanced templates](docs/en/image-templates.md)
- [Custom backgrounds and pixel coordinates](docs/en/pixel-templates.md)
- [PlaceholderAPI variables](docs/en/placeholders.md)
- [Server startup notices](docs/en/startup-notices.md)
- [Troubleshooting](docs/en/troubleshooting.md)
- [Platform compatibility](docs/en/compatibility.md)
- [Upgrading and automatic updates](docs/en/updating.md)
- [Production security checklist](docs/en/security.md)
- [Building and development](docs/en/development.md)
- [Plugin API and whitelist management](docs/en/api.md)
- [All documentation](docs/en/README.md)

## Support and feedback

The project is still under development. Report problems through [GitHub Issues](https://github.com/hutuyee/ShitBot/issues), including your platform, server version, Java version, and relevant logs.

ShitBot does not compete with EasyBot.

## Statistics

- [bStats Bukkit](https://bstats.org/plugin/bukkit/ShitBot/33865)
- [bStats BungeeCord](https://bstats.org/plugin/bungeecord/ShitBot/33866)
- [bStats Velocity](https://bstats.org/plugin/velocity/ShitBot/33868)
- ![bStats Bukkit](https://bstats.org/signatures/bukkit/ShitBot.svg)

## License

[MIT](LICENSE)
