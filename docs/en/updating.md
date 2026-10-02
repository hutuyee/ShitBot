# Upgrading and automatic updates

[简体中文](../updating.md) | **English**

## Before upgrading

1. Read the target version's [release notes](https://github.com/hutuyee/ShitBot/releases).
2. Back up the database.
3. Back up `config.yml`, `commands.yml`, `lang/`, `templates/`, `image-templates/`, and custom image resources.
4. Check whether the proxy and backends must update together.
5. Do not directly copy SQLite while the server is running.

If the proxy/backend protocol changes, update the proxy and all Spigot backends together. Mixed protocol versions may break commands, TPS queries, or coordinated updates.

Instances using advanced templates also need the matching ShitBotRenderer in the official release. After the platform plugin updates, the next startup checks the new component cache. The component downloads only when advanced templates are enabled.

## Manual upgrade

1. Stop the server or proxy.
2. Download the new JAR for your platform.
3. Move the old JAR out, leaving only one ShitBot platform JAR.
4. Put the new JAR in place.
5. Start and inspect configuration/database migration logs.
6. Review newly added fields saved automatically to configuration, language files, and image themes, and adjust as needed.
7. Run `/shitbot status`.
8. Verify OneBot, binding, forwarding, and shortcuts.

Do not hot-unload the old JAR and hot-load the new one with a plugin manager.

Startup and reload save missing keys and `null` values in configuration, language files, and image themes while retaining existing values; see [Configuration](configuration.md). When upgrading from `config-version: 1`, the first load imports replies, notices, built-in aliases/usage, and image titles from the old `config.yml` into `lang/zh_CN.yml` before completing the main configuration. Review the import before removing obsolete text settings yourself. Completion is recorded in `_migration.legacy-config-v1`, preventing repeated overwrites on reload.

## /shitbot update

Administrators with `shitbot.admin` can run:

```text
/shitbot update
```

The updater:

1. Queries the latest GitHub release.
2. Selects the JAR for the current platform.
3. Downloads the JAR, `.sha256`, and `.sig`.
4. Verifies download addresses, size, and SHA-256.
5. Verifies the detached RSA signature with the embedded public key.
6. Checks the platform descriptor, main class, and embedded version.
7. Backs up the current JAR as `.bak` in the same directory.
8. Replaces the JAR and asks for a manual restart.

This does not hot-reload the plugin. Restart the server or proxy normally afterward.

If any verification fails, the current JAR is retained. Do not bypass verification by removing `.sha256`, `.sig`, or altering release files.

## Coordinated network updates

Running `/shitbot update` on BungeeCord sends the same release's Spigot JAR and verification metadata to configured `backend-transport.endpoints`.

- Each backend verifies and replaces its own JAR independently.
- The proxy reports each backend's result.
- Backends without an endpoint are not scanned or updated.
- You must still restart the proxy and backends manually.
- Backend-mode Spigot does not independently check GitHub; the proxy handles notification and distribution.

Before a coordinated update, confirm the channel works and back up databases and configurations on all instances.

## Automatic checks

After startup, the plugin checks GitHub releases in the background without occupying the server's main thread. Changed results are written to `update-cache.json` in the data directory.

Administrators with `shitbot.admin` receive an available-version notice and release link when joining. Automatic checks only notify; they do not replace the plugin without an update command.

## Update failures

Check that:

- The instance can reach `api.github.com` and GitHub release download hosts.
- The release contains the current platform's JAR.
- If advanced templates are enabled, the matching ShitBotRenderer is also present.
- Matching `.jar.sha256` and `.jar.sig` files exist.
- The JAR, checksum, and signature all come from the same release.
- The plugin directory is writable.
- The current JAR path can be identified and is not locked by another program.
- The proxy/backend channel is healthy.

If automatic updating fails, preserve the logs and use a manual upgrade, still downloading from the official release.

## Release maintainers: signing keys

Server owners do not need to configure update keys. The public key is embedded in ShitBotCore. Store the private key only in the GitHub Actions `SHITBOT_UPDATE_PRIVATE_KEY` secret.

To generate or rotate a key:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out update-private-key.pem
openssl pkey -in update-private-key.pem -pubout -out update-public-key.pem
```

When rotating:

1. Update ShitBotCore's embedded public key.
2. Update the private key in the repository secret.
3. Deploy the version containing the new public key through a trusted manual upgrade.
4. Sign subsequent releases with the new private key.

Never commit the private key, bundle it in a JAR, or distribute it to server owners.
