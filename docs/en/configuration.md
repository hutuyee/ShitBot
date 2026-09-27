# Configuration

[简体中文](../configuration.md) | **English**

On first startup, ShitBot generates these files in its data directory:

- `config.yml`: language, OneBot, forwarding, binding, database, images, and inventories.
- `commands.yml`: TPS, QQ shortcuts, advanced image commands, and the proxy/backend command channel.
- `lang/zh_CN.yml` and `lang/en_US.yml`: the main user-facing text, built-in aliases, and image text.
- `templates/default.yml`: default layouts and colors for online lists and inventory images.

The configuration files include comments. Run `/shitbot reload` after editing. Restart normally after changing the plugin JAR, Java, TLS certificates, or server software.

## Language files

Use a language filename without its extension at the top level of `config.yml`:

```yaml
language: "en_US"
```

The default is `zh_CN`. To add another language, copy `lang/zh_CN.yml` or `lang/en_US.yml`, rename it (for example, `zh_TW.yml`), translate it, and set `language: "zh_TW"`. Names may contain only letters, digits, underscores, and hyphens.

Missing keys fall back to `zh_CN.yml`, so custom languages continue working as new text is added. Still compare them with the latest bundled files periodically and add missing keys. Preserve placeholders such as `%player%` and `%result%`. Minecraft text supports `&` color codes.

## Debug mode

For local debugging, enable this top-level setting:

```yaml
debug: true
```

This logs the OneBot authentication token, outgoing QQ request JSON, and raw incoming JSON. When advanced templates are enabled, it loads only `components/image-renderer/<version>/ShitBotRenderer-<version>.jar` in the plugin data directory and does not download the component. The version comes from `custom-image-templates.component.version`, or the current platform plugin version if empty.

Local JARs still undergo size, service entry point, and embedded version checks, but SHA-256, `.sig`, and public key verification are skipped. A missing or invalid local JAR fails immediately without falling back to a release download. Disable debug mode afterward because the logs contain sensitive tokens and message content.

### Migrating text from an old configuration

When loading a `config-version: 1` configuration, ShitBot automatically writes these old values to the data directory's `lang/zh_CN.yml`:

- All replies and kick messages under `messages`.
- Group welcome and server startup notice text.
- Binding, online image, and inventory command aliases and usage.
- Online image and inventory image titles.

The old `config.yml` is neither rewritten nor deleted. After a successful migration, `zh_CN.yml` receives this internal marker to prevent subsequent reloads from overwriting the language file:

```yaml
_migration:
  legacy-config-v1: true
```

After reviewing the language file, you can remove obsolete `messages`, `message`, `aliases`, `usage`, and image `title` entries from the old configuration. Edit the language file directly afterward. To deliberately import the old configuration again, remove the marker and run `/shitbot reload`.

## OneBot connection

```yaml
onebot:
  enabled: true
  websocket-url: "ws://127.0.0.1:3001"
  access-token: ""
  allow-insecure-remote-websocket: false
  allow-all-groups: false
  allowed-group-ids:
    - 123456789
```

| Setting | Meaning |
| --- | --- |
| `enabled` | Whether to connect to OneBot |
| `websocket-url` | OneBot v11 forward WebSocket address |
| `access-token` | If nonempty, authenticate with `Authorization: Bearer <token>` |
| `allow-insecure-remote-websocket` | Allow plaintext `ws://` on non-loopback addresses; rejected by default |
| `allow-all-groups` | Accept messages from any group |
| `allowed-group-ids` | Allowed groups and the destinations for game-to-QQ forwarding |

Connection, action, heartbeat, and reconnection settings normally work with their defaults. For high latency, consider increasing `connect-timeout-seconds`, `action-timeout-seconds`, and `heartbeat-timeout-seconds`.

## Group notices

Notices are configured under `onebot.notices`.

### Startup notices

```yaml
onebot:
  notices:
    server-startup:
      enabled: true
      target-server: ""
      check-interval-seconds: 5
```

- Spigot standalone and Nukkit-MOT: leave `target-server` empty to notify after this instance starts and connects to OneBot.
- BungeeCord/Velocity: an empty value announces proxy startup. A backend name from the proxy configuration makes the proxy wait until that backend first becomes reachable.
- Notices go to all `allowed-group-ids`.

See [Server startup notices](startup-notices.md) for examples, status ping semantics, and pending notices across reloads.

### Group welcomes and unbinding on leave

```yaml
onebot:
  notices:
    group-join-welcome:
      enabled: true
    group-leave-unbind:
      enabled: false
```

`group-leave-unbind` deletes a member's QQ bindings when they leave an allowed group. Enable this destructive rule only if it matches your server policy. Startup and welcome text is under `notices` in the selected language file.

## Built-in QQ commands

`onebot.commands` controls three built-in features:

```yaml
onebot:
  commands:
    bind:
      enabled: true
    online-image:
      enabled: true
    inventory:
      enabled: true
```

Switches are in `config.yml`; aliases and usage are under `commands` in the language file. Aliases must match the group message. TPS and other shortcut switches, permissions, and execution behavior are in `commands.yml`, with display text under the language file's `console` section.

## Chat forwarding

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

- `enabled` controls each direction separately.
- `require-prefix: true` forwards only messages with the configured prefix.
- `require-prefix: false` forwards all nonempty messages.
- The trailing space in `prefix` is part of the prefix. An empty prefix matches all nonempty messages.
- Game messages go to every group in `onebot.allowed-group-ids`.

`media-mode` options:

| Value | Behavior |
| --- | --- |
| `browser` | Java clients show clickable media labels that open a browser; Nukkit-MOT shows labels and raw URLs |
| `picturebridge` | Adds PictureBridge markers for images and emoji on Java clients; players without the mod can still use web links |

`picturebridge` requires the [PictureBridge](https://github.com/hutuyee/PictureBridge) client mod. Nukkit-MOT does not use this protocol.

## Account binding

```yaml
binding:
  enabled: true
  allow-multiple-ids-per-qq: true
  maximum-ids-per-qq: 5
  code-length: 6
  expire-minutes: 10
```

- An unbound player joining a server protected by binding receives a one-time code.
- With `en_US`, send `bind <Minecraft ID> <code>` in QQ; with `zh_CN`, use `绑定 <游戏ID> <验证码>`.
- Minecraft IDs match exactly, including case.
- `allow-multiple-ids-per-qq: false` allows only one Minecraft ID per QQ account.
- `maximum-ids-per-qq` applies to both normal binding and EasyBot imports.

Code attempt limits, cooldowns, and the alphabet have secure defaults and normally need no changes.

## Database

```yaml
database:
  type: "sqlite"
  sqlite:
    file: "shitbot.db"
```

SQLite is suitable for one instance. A proxy with multiple backends must use MySQL, with every instance connected to the same database. See [Databases and migration](database.md).

## Online status images

`image` controls online status image settings such as server name, font, width, players per row, maximum players, and avatar service. Translated titles are managed in the language files.

Common settings:

```yaml
image:
  renderer: "java"
  template: "default"
  server-name: "Server-Status"
  font-name: "Microsoft YaHei"
  players-per-row: 5
  maximum-players: 200
  avatar:
    enabled: true
    url-template: "https://mc-heads.net/avatar/%player%/64"
```

On Linux, install the font named by `font-name` to avoid missing or substituted Chinese glyphs. For Bedrock players on Nukkit-MOT, you can use an avatar service that supports Xbox IDs.

### Lightweight theme files

The generated `templates/default.yml` contains both `online` and `inventory` themes. To maintain multiple appearances, copy it to a named file such as `templates/ocean.yml`, then select the name without `.yml`:

```yaml
image:
  template: "ocean"

inventory:
  template: "ocean"
```

`image.template` and `inventory.template` can select different files. Names may contain only letters, digits, underscores, and hyphens. Each missing field falls back to `templates/default.yml`, so a custom file can contain only the fields you want to override.

Themes control layout dimensions, font sizes, corner radii, strokes, background gradients, cards, text, status colors, slots, and placeholder avatars. Supported colors:

- `#RRGGBB`: opaque.
- `#AARRGGBB`: alpha first, with `AA` controlling opacity.

Translated text and time formats remain under `image` and `inventory` in `lang/*.yml`. Runtime settings such as width, fonts, avatar requests, caches, and output files remain in `config.yml`. Numeric theme values are bounded. Apply changes with `/shitbot reload`.

### Advanced scene templates

The advanced system is separate from the lightweight `templates/*.yml` themes. It is disabled by default and does not download its component:

```yaml
image:
  # java uses the built-in image; custom uses a published advanced template.
  renderer: "java"
  custom-template: "online-status"

custom-image-templates:
  enabled: false
  directory: "image-templates"
  remote-images:
    enabled: true
```

ShitBot loads the matching `ShitBotRenderer` only when `custom-image-templates.enabled: true`. Normal mode checks the cache and downloads the component, checksum, and signature when needed. With `debug: true`, it uses only the local JAR in the matching version directory. With `image.renderer: "java"`, the built-in online image remains active while the advanced editor, custom QQ commands, and plugin API can be used separately. With `custom`, `server status` (`服务器状态` in Chinese) and `/shitbot image` use the published template selected by `image.custom-template`.

HTTPS remote images are enabled by default, and the default online template gets player avatars through `image.avatar.url-template`. This setting does not load the component while the main advanced-template switch is off. An explicit `remote-images.enabled: false` in an older configuration is preserved; set it to `true` and reload if you want avatars.

See [Image rendering and advanced templates](image-templates.md) for component settings, scene YAML, providers, the editor, and the API.

## Inventory queries

`inventory` controls the theme, snapshot interval, offline retention, rendering concurrency, and texture sources. Networks need backends to save snapshots and the proxy to read them through shared MySQL.

See [Inventory queries and textures](inventory.md) for resource packs, client JARs, mod items, and custom icons.

## Custom text

The selected `lang/*.yml` controls replies, kick messages, image text, media labels, admin feedback, and console request results. Common placeholders:

| Placeholder | Meaning |
| --- | --- |
| `%player%` | Minecraft ID |
| `%code%` | Binding verification code |
| `%qq%` | QQ ID |
| `%expire_minutes%` | Code lifetime |
| `%maximum_ids%` | Maximum bound IDs per QQ |
| `%at%` / `%艾特%` | Mention the sender in a QQ reply |

Use `|` for YAML multiline messages and keep indentation consistent. Do not delete or translate keys; edit only their values.

## commands.yml

`commands.yml` contains:

- The global QQ shortcut switch and cooldown.
- TPS settings, permissions, and target.
- Custom shortcuts, permissions, execution locations, and commands.
- Advanced image aliases, bound-player selection, backend targets, permissions, and separate cooldowns.
- The authenticated BungeeCord/Velocity-to-Spigot channel.

Built-in TPS and `luckperms-editor` aliases and replies are in the language file. For a new shortcut, you can put fallback `aliases`, `message`, and `failed` fields in `commands.yml`, or define them under `console.shortcuts.<name>` in the language file. See [Commands and permissions](commands.md) and [Proxies and backend servers](proxy-backend.md).
