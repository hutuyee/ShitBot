# PlaceholderAPI variables

[简体中文](../placeholders.md) | **English**

ShitBot uses PAPI in two ways: exposing its own status to scoreboards, menus, and other plugins, and reading other plugins' placeholders in advanced image templates.

## Display ShitBot status in scoreboards or menus

Install a PlaceholderAPI version compatible with your Spigot/Paper/Folia server and Java version, then start ShitBotSpigot normally. ShitBot registers its built-in expansion automatically. No separate `shitbot` expansion download or advanced renderer is required.

| Placeholder | Value |
| --- | --- |
| `%shitbot_version%` | ShitBot version |
| `%shitbot_ready%` | Whether the runtime and database are ready: `true` / `false` |
| `%shitbot_connected%` | Whether this instance is connected to OneBot: `true` / `false`; usually false on a backend |
| `%shitbot_whitelisted%` | Whether the player has a ShitBot whitelist entry, including entries without QQ |
| `%shitbot_bound%` | Whether the player has a QQ owner; `false` for an entry without QQ |
| `%shitbot_qq%` | The player's QQ ID; empty if unbound or whitelisted without QQ |

Player placeholders query exact, case-sensitive Minecraft IDs. Synchronous PAPI callbacks do not wait for the database: the first read starts an asynchronous query and returns an empty string; later reads use a cache lasting at most 5 seconds. Missing player context, an unready runtime, or a failed query also returns an empty string. The cache holds at most 512 players and switches to the new runtime after reload. These placeholders are for display; login and command authorization still use the database service APIs.

`%shitbot_qq%` exposes the actual QQ ID. Use it only where you intend to display that information. It does not assign a fictitious QQ owner to a whitelist entry without QQ.

## Use other plugins' placeholders in images

Explicitly declare the `papi` provider in the advanced template's `manifest.yml`:

```yaml
providers:
  - id: papi
    player: "${context.player}"
    server: "${context.server}"
    placeholders:
      display_name: "%player_displayname%"
      balance: "%vault_eco_balance_formatted%"
```

Use `${data.papi.display_name}` or `${data.papi.balance}` in `scene.yml` text nodes. Install the plugins and expansions that supply those placeholders. Unresolved placeholders, missing plugins, offline players, and timeouts produce explicit errors.

BungeeCord/Velocity asks the target Spigot backend to resolve placeholders through the authenticated channel. The proxy does not load Bukkit's PlaceholderAPI. `server` is the backend name in the proxy configuration. Nukkit-MOT does not support Bukkit PAPI; use built-in ShitBot providers or custom third-party providers.

See [Image rendering and advanced templates](image-templates.md#placeholderapi-and-proxy-backends) for limits, caching, scheduling, error display, and command examples. Expansion integration follows [PlaceholderAPI's internal expansion interface](https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/).
