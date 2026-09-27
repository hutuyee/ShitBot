# Commands and permissions

[简体中文](../commands.md) | **English**

`/shitbot whitelist` is available on every platform and requires `shitbot.admin`. Use `/shitbot whitelist add - Steve` for a whitelist entry without QQ, or `/shitbot whitelist add 123456789 Steve` to add a QQ binding directly. See [Plugin API and whitelist management](api.md#administrator-commands) for removal, pagination, and QQ lookups.

QQ examples on this page use `language: "en_US"`. For Chinese aliases, use `zh_CN` and the [Chinese command reference](../commands.md).

## Administration commands

The main command is `/shitbot`, with alias `/sbot`.

| Command | Purpose | Permission |
| --- | --- | --- |
| `/shitbot status` | Show database, OneBot, and runtime status | None |
| `/shitbot reload` | Reload configuration, database, OneBot, images, and command services | `shitbot.admin` |
| `/shitbot update` | Download and install an update for this platform | `shitbot.admin` |
| `/shitbot image` | Generate an online status image manually | `shitbot.admin` |
| `/shitbot editor` | Create a short-lived, one-time editor login URL | `shitbot.admin` |
| `/shitbot migrate easybot [EasyBot.db]` | Import EasyBot bindings | `shitbot.admin` |

Spigot and Nukkit-MOT grant `shitbot.admin` to OPs by default. BungeeCord and Velocity check it through their platform permission systems.

`/shitbot reload` creates a new runtime first and retains the old one if loading fails. Restart normally after JAR, Java, or TLS file changes.

## QQ binding

Built-in English aliases:

```text
bind <Minecraft ID> <verification code>
/bind <Minecraft ID> <verification code>
```

The code is generated when an unbound player tries to join. The `binding` settings control QQ/Minecraft ID binding rules.

## Online status and inventories

Built-in English commands:

```text
server status
online players
inventory
my inventory
inventory <Minecraft ID>
my inventory <Minecraft ID>
```

When a Minecraft ID is supplied, ShitBot verifies that it belongs to the sender's QQ account. You cannot query another user's character.

## TPS

Configure TPS in `commands.yml`:

```yaml
tps:
  enabled: true
  permission: ""
  server: ""
```

Aliases, success text, and failure text are under `console.tps` in the selected language file.

- Standalone servers and Nukkit-MOT query locally.
- Proxies can specify a default backend in `server`.
- Group members can append a backend, for example `TPS survival`.
- An empty `permission` skips the game permission check, but group restrictions and cooldowns still apply.

## QQ shortcuts

Define each shortcut under `shortcuts` in `commands.yml`:

```yaml
shortcuts:
  luckperms-editor:
    enabled: true
    command: "lp editor"
    permission: "shitbot.admin"
    allow-unbound: false
    target: "backend"
    server: ""
    capture-seconds: 5
```

Built-in aliases and reply templates are under `console.shortcuts.<name>` in the language file. For a new custom shortcut without a corresponding language entry, `aliases`, `message`, and `failed` in `commands.yml` are fallbacks.

| Setting | Meaning |
| --- | --- |
| `aliases` | Trigger text in QQ |
| `command` | Console command without the leading `/` |
| `permission` | Game permission required for the bound character; empty skips the game permission check |
| `allow-unbound` | Allow unbound QQ accounts; off by default |
| `target` | `backend` runs on a Bukkit backend; `proxy` runs on BungeeCord/Velocity |
| `server` | Default backend; if empty, prefer the bound character's current backend |
| `capture-seconds` | Time to wait for and collect new console log output |

An empty `permission` still requires a bound character by default. Only an explicit `allow-unbound: true` permits unbound group members; use it carefully because it weakens the permission boundary.

Group members can override `server` by appending a backend:

```text
lp editor survival
```

## Advanced QQ image commands

Advanced templates do not register commands themselves. Declare each group entry explicitly under `image-templates.commands` in `commands.yml`:

```yaml
image-templates:
  commands:
    player-card:
      enabled: true
      aliases:
        - "my card"
      template: "player-card"
      player-source: "argument"
      target-server: "survival"
      permission: "shitbot.image.player-card"
      cooldown-seconds: 10
      usage: "%at% Usage: my card <bound character>"
      failed: "%at% Image generation failed: %result%"
```

| Setting | Meaning |
| --- | --- |
| `aliases` | Group triggers; avoid conflicts with built-in commands, TPS, or shortcuts |
| `template` | Published template ID |
| `player-source` | `bound` selects the most recently bound character; `argument` verifies the supplied character belongs to the sender; `none` does not read bindings |
| `target-server` | Spigot backend handling permissions and PAPI in proxy deployments |
| `permission` | Permission required for the selected character; empty skips the check |
| `cooldown-seconds` | Separate cooldown for this template command, keyed by group and QQ |
| `usage` / `failed` | Invalid-argument and rendering-failure replies; support `%at%`, `%result%`, `%command%`, and `%server%` |

Permission requests only check permissions; they do not execute console commands. `player-source: none` with a nonempty `permission` cannot authorize a request because no character is available to check. Enable `custom-image-templates.enabled: true` in `config.yml` as well. See [Image rendering and advanced templates](image-templates.md).

## Permission resolution

For a shortcut with a `permission`, a Spigot backend checks:

1. Bukkit permissions if the character is online.
2. LuckPerms, then Vault, then offline OP status if the character is offline.

Proxy-local commands use proxy permissions; LuckPerms on the proxy can provide offline permissions. Nukkit-MOT uses its platform permissions for online characters and LuckPerms, if installed, for offline ones.

## Command output

Before executing a shortcut, ShitBot records the log position. After `capture-seconds`, it reads newly appended content:

- Bukkit reads `logs/latest.log`.
- Nukkit-MOT reads `logs/server.log`.
- Original log files are not modified.
- Each reply retains at most 100 lines and 4,000 characters.
- Player chat, joins/leaves, and command execution logs are removed from the reply copy.
- Player names, UUIDs, IPs, tokens, passwords, and database addresses are redacted in the reply copy.

If execution succeeds without new log output, the bot says so explicitly.

## Applying changes

After saving `commands.yml` or a language file:

```text
/shitbot reload
```

For proxy/backend deployments, finish editing both sides before reloading each instance. Restart after changing transport addresses, certificates, or firewall settings.
