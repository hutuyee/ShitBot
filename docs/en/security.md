# Production security checklist

[简体中文](../security.md) | **English**

## OneBot

- Set a nonempty, unguessable access token in production.
- Restrict `allowed-group-ids`; avoid `allow-all-groups: true`.
- Use `ws://127.0.0.1` on the same host.
- Use `wss://` between hosts.
- Consider `allow-insecure-remote-websocket` only inside a trusted encrypted tunnel.
- Restrict access to the OneBot port with a firewall.
- Do not expose tokens in logs, screenshots, or issues.

OneBot limits incoming messages to 2 MiB during WebSocket decoding, before oversized messages are fully allocated. The plaintext loopback exemption accepts only `localhost`, explicit IPv6 loopback addresses, or canonical `127.x.x.x` IPv4 literals, never domain names beginning with `127.`.

## Database

- Create a dedicated ShitBot database account.
- Grant only required permissions on the target database.
- Use `sslMode=VERIFY_IDENTITY` for remote MySQL.
- Keep `allowPublicKeyRetrieval=false`.
- Never share SQLite files between processes.
- Back up before upgrades, migrations, or platform changes.
- Do not expose database URLs, usernames, or passwords.

Remote MySQL TLS checks parse individual parameters and give `sslMode` precedence over legacy SSL flags. Duplicate security parameters or noncanonical key casing do not satisfy the check. Legacy-only configurations must explicitly set `useSSL=true` together with either `requireSSL=true` or `verifyServerCertificate=true`. The existing explicit `allow-insecure-remote-mysql` override remains available.

## Proxy/backend channel

- Use a different random token of at least 16 characters for each backend.
- Bind same-host listeners to `127.0.0.1`.
- Enable TLS between machines.
- Put only actual proxy IPs in `allowed-proxy-addresses`.
- Allow only the proxy to reach the listener port through the firewall.
- Do not expose the listener directly to the Internet.
- Keep the HMAC token even with TLS.
- Enable mutual TLS when stronger identity verification is needed.

HMAC provides request authentication and integrity, not encryption. Protect cross-machine traffic with TLS or a trusted encrypted tunnel.

## QQ shortcuts

- Configure only console commands that are actually needed.
- Set `permission` for sensitive shortcuts.
- Keep `allow-unbound: false`.
- Restrict groups and command aliases.
- Do not concatenate arbitrary group input into unrestricted console commands.
- Review LuckPerms, Vault, and OP permissions regularly.
- Monitor cooldowns, timeouts, and queues against actual load.

An empty `permission` only skips the game permission check. `allow-unbound: true` permits unbound members to call the command, so reassess its risk before enabling it.

## Advanced image templates

- Keep `custom-image-templates.enabled: false` if unnecessary; no renderer is downloaded or loaded.
- Use the matching official `ShitBotRenderer`, `.sha256`, and `.sig`; do not bypass signatures.
- Keep the editor on loopback. For remote access, use an HTTPS reverse proxy, preserve the original `Host`, and do not expose the internal port.
- Treat `/shitbot editor` URLs as short-lived, one-time credentials; do not share them in group chats, logs, or issues.
- Remote images are enabled by default for avatars but remain subject to HTTPS, address, and resource limits. Disable `custom-image-templates.remote-images.enabled` if network images are unnecessary.
- Do not edit `versions/` or `published.yml` manually. Production rendering should use published snapshots.
- Do not raise canvas, pixel, layer, loop, asset, timeout, thread, or queue limits without bounds.
- Custom providers must switch to the correct thread before accessing platform APIs and return only needed rendering data.

The template engine does not execute uploaded HTML, JavaScript, or arbitrary server commands and contains no browser engine. Image commands and console shortcuts in `commands.yml` are separate mechanisms.

Remote template images connect directly over HTTPS port 443 to a checked public address. Each redirect is checked again, and the connection is pinned to the checked address while retaining TLS verification for the original hostname. This path bypasses JVM HTTP/SOCKS proxies so proxy-side DNS cannot bypass the address checks. Downloads use two workers and 16 queue slots; the cache is limited to 128 entries and a total of 16 Mi pixels, and network reads are bounded by the render timeout. Base64 size and image dimensions are checked before decoding.

Template YAML normalization rejects recursive aliases and enforces a maximum depth of 64 and 65,536 expanded nodes, counting alias expansion. Asset uploads use randomly named, exclusively created temporary files and reject symbolic-link destinations. Source reads also reject symbolic-link files.

## Account binding

- Keep verification-code expiry and attempt limits.
- Do not reduce `code-alphabet` to a very small character set.
- Set `maximum-ids-per-qq` to match your server policy.
- Confirm that leaving a group should remove bindings before enabling `group-leave-unbind`.
- Do not bypass binding checks when the database fails.
- Move EasyBot database copies out after migration.

## Updates

- Download JARs only from official GitHub releases.
- Preserve the `.sha256` and `.sig` verification chain.
- Investigate update failures instead of bypassing signatures.
- Update proxies and backends together.
- Restart normally after replacing a JAR; do not hot-load with a plugin manager.
- Store the release private key only in repository secrets.

## Logs and backups

- Remove tokens, passwords, database addresses, and private player data before sharing logs.
- QQ shortcut log redaction supplements, rather than replaces, least privilege.
- Encrypt database backups and restrict access.
- Configuration backups also contain sensitive information.
- Do not commit production configuration, databases, or signing private keys to Git.
- Regularly confirm that backups can be restored.

## Network and resources

- Restrict sources allowed to reach database, OneBot, backend listener, and management ports.
- Set connection and read timeouts for remote services.
- Keep database, rendering, provider, and command queues bounded.
- Use trusted sources for inventory resource packs and client JARs.
- Use HTTPS for avatars and external images.
