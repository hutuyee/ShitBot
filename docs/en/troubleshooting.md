# Troubleshooting

[简体中文](../troubleshooting.md) | **English**

Start with:

```text
/shitbot status
```

Record the platform, server version, Java version, and complete console logs around the problem.

## OneBot stays disconnected

Check that:

1. OneBot v11 forward WebSocket is enabled.
2. The protocol, address, and port in `onebot.websocket-url` are correct.
3. `access-token` matches OneBot.
4. `127.0.0.1` refers to the machine running ShitBot, not a different OneBot host.
5. Cross-host connections use `wss://`; remote `ws://` is rejected by default.
6. Java trusts the `wss://` certificate.
7. The firewall permits the Minecraft instance to connect to OneBot.
8. The OneBot implementation supports v11 forward WebSocket.

## QQ commands get no reply

Check that:

- The group is in `onebot.allowed-group-ids`, or `allow-all-groups` is deliberately enabled.
- OneBot reports a group message event.
- `onebot.commands.<feature>.enabled` is on.
- The input matches an alias in the selected language file. English aliases require `language: "en_US"`.
- The command is not on cooldown.
- The bot can send group messages and images.
- `/shitbot status` reports a healthy database and OneBot connection.

## #qq or #mc does not forward

Each direction has its own switch:

```yaml
forwarding:
  game-to-group:
    enabled: true
  group-to-game:
    enabled: true
```

With `require-prefix: true`, include the entire prefix, including its trailing space. Game-to-QQ destinations come from `allowed-group-ids`.

## A bound player is still blocked

Check that:

- The Minecraft ID in the QQ command exactly matches the actual name, including case.
- The binding command uses the latest code generated for this login attempt.
- The code has not expired.
- The QQ account has not exceeded `maximum-ids-per-qq`.
- The proxy and backends share the same MySQL database.
- The player's binding exists in the database.
- Database charset, collation, and schema have not been manually altered.

## Database connection fails

For SQLite:

- The plugin data directory is writable.
- No other process is opening the shared file.
- Enough disk space is available.

For MySQL:

- Address, port, database, username, and password are correct.
- The account has permissions on the target database.
- Remote connections use `sslMode=VERIFY_IDENTITY`.
- Java trusts the database certificate.
- The server can reach `repo.maven.apache.org`, or a compatible JDBC driver is available.
- The firewall and MySQL bind address permit the connection.

## The proxy reports no available backend

Check that:

1. Backend Spigot uses `deployment.role: "backend"`.
2. Its listener is enabled.
3. The endpoint name matches the backend's `server-name`.
4. Address, port, and token agree on both sides.
5. The token has at least 16 characters.
6. `allowed-proxy-addresses` contains the actual proxy IP.
7. Cross-machine connections use TLS or an explicitly allowed encrypted tunnel.
8. The firewall allows only the proxy to access the listener.
9. Proxy and backend system clocks are correct.

See [Proxies and backend servers](proxy-backend.md).

## A shortcut reports no permission

- The sender must have a bound character unless `allow-unbound` is explicitly enabled.
- The character must have the shortcut's `permission`.
- Offline checks need LuckPerms, Vault, or available offline OP information.
- Proxy-local and backend commands use different platform permission systems.
- An empty `permission` skips game permission checks; it does not automatically allow unbound QQ accounts.

## Inventory snapshots or textures are missing

- The player must have joined a backend responsible for snapshots at least once.
- `inventory.enabled` must be on.
- All network instances must share MySQL.
- The renderer must be able to read resource packs, client JARs, or exported icons.
- Old snapshots may need a new login to capture new fields and player head data.

See [Inventory queries and textures](inventory.md).

## Chinese text in images is broken

Install the fonts selected by `image.font-name` and `inventory.font-name`. Linux usually does not include `Microsoft YaHei`; install a font or select an available Chinese font.

## Advanced templates fail to start

First decide whether you need the advanced system. If not, keep `custom-image-templates.enabled: false` and `image.renderer: "java"`.

Otherwise, check that:

- `image.renderer: "custom"` is paired with the advanced-template switch being enabled.
- With `debug: true`, `components/image-renderer/<version>/ShitBotRenderer-<version>.jar` exists in the plugin data directory with valid metadata and version. Debug mode does not download it or require `.sha256` and `.sig`.
- With `debug: false`, the release includes the matching renderer JAR, checksum, and signature.
- With `debug: false`, the server can access GitHub release download hosts.
- With `debug: false`, `components/image-renderer/<version>/` is writable and cached files have not been manually replaced.
- Download size, assets, canvas, pixels, layers, loops, rendering time, and queues remain within limits.
- The template is published and its ID in `image.custom-template` or the group command is correct.
- Remote images are enabled. If an old configuration still has `custom-image-templates.remote-images.enabled: false`, change it to `true` and reload before using HTTPS avatars/images. The default online template also needs `image.avatar.enabled: true` to supply avatar URLs.
- PAPI templates have PlaceholderAPI on the Spigot backend, an online player, and correct endpoint/`target-server` settings.

If the editor does not open, enable `editor.enabled: true` and run `/shitbot editor` again for an unused login URL. Reverse proxies must use HTTPS and preserve the original `Host`; keep the internal listener on loopback.

## Reload fails

The old runtime stays active when `/shitbot reload` fails. Find the earliest configuration or connection error in the console, fix it, and reload again.

Restart directly after:

- Replacing the plugin JAR.
- Changing Java or server software.
- Replacing TLS key stores or trust stores.
- Changing underlying networking, firewall, or database services.

## Still unresolved

Open a [GitHub issue](https://github.com/hutuyee/ShitBot/issues) with:

- ShitBot version.
- Platform and server version.
- Java version.
- Standalone, proxy, or proxy/backend deployment.
- Logs with tokens, passwords, database addresses, and private player data removed.
- Reliable reproduction steps.

Do not ask for ShitBot support in LLBot groups.
