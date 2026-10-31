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
6. Compare against the pre-upgrade files in `config-backups/`, review newly added fields saved automatically to configuration, language files, and image themes, and adjust as needed.
7. Run `/shitbot status`.
8. Verify OneBot, binding, forwarding, and shortcuts.

Do not hot-unload the old JAR and hot-load the new one with a plugin manager.

When the plugin version changes, existing `config.yml`, `commands.yml`, and YAML files under `lang/` and `templates/` are backed up before configuration is read or modified. Backups live in `config-backups/` inside the plugin data directory and retain original contents and relative paths. Existing installations also receive a snapshot when the backup feature is first used. Each automatic configuration overwrite additionally preserves the original file, and a backup failure stops that write. See [Automatic configuration backups](configuration.md#automatic-configuration-backups) for locations, retention, and restoration.

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
7. Backs up current configuration to `config-backups/` in the data directory and logs its location.
8. Backs up the current JAR as `.bak` in the same directory.
9. Saves the Spigot JAR in `plugins` using its release filename, or replaces the current JAR on other platforms, and asks for a normal restart.

Spigot, Paper, and Folia download the new JAR directly to the plugin directory. They no longer use `plugins/update/` or `settings.update-folder` in `bukkit.yml`. For example, updating a running `ShitBotSpigot-1.1.5.jar` to 1.2.0 adds `plugins/ShitBotSpigot-1.2.0.jar`. The updater does not overwrite or delete the running JAR, avoiding Windows file locks. The new JAR reaches its final path only after all verification and backup steps succeed.

Once the new version loads, it scans JARs in `plugins`, identifies older ShitBot copies by their descriptor name, main class, and version, and deletes versions older than the loaded one. It retains the running file, equal or newer versions, files with unrecognized versions, other plugins, and `.bak` backups. Successful deletions and cleanup failures are logged; a deletion failure does not interrupt startup.

The command reports a downloaded update and shows the new file's path. Repeating the command in the same process reports an already-downloaded update while the file remains present, or downloads it again if it was removed. The new JAR must have a different path from the running JAR. If an administrator has already renamed the old JAR to the new release's filename, the updater fails and retains the old file.

Bukkit does not guarantee that the highest version is selected when duplicate plugin names exist; see [Bukkit's plugin loading code](https://github.com/Bukkit/Bukkit/blob/master/src/main/java/org/bukkit/plugin/SimplePluginManager.java). If the server still loads the older version after restart, cleanup keeps the newer JAR and logs a request to stop the server, remove the old JAR, and restart. Earlier versions without this cleanup feature require the manual upgrade procedure.

BungeeCord, Velocity, and Nukkit-MOT still replace the verified JAR on disk and load it after restart. If an older Spigot updater fails because Windows locks the JAR, stop the server and manually install a version containing the new workflow. If a previous update left a JAR in `plugins/update/`, remove that leftover file while the server is stopped so the server does not continue the old replacement workflow.

This does not hot-reload the plugin. Restart the server or proxy normally afterward.

If verification or configuration backup fails, the current JAR is retained. Do not bypass verification by removing `.sha256`, `.sig`, or altering release files.

## Coordinated network updates

Running `/shitbot update` on BungeeCord sends the same release's Spigot JAR and verification metadata to configured `backend-transport.endpoints`.

- Each Spigot backend independently verifies the new JAR, backs up its configuration and current JAR, and downloads the new version to its own `plugins` directory. The new version deletes older JARs after it loads.
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
- The plugin directory is writable. The new Spigot filename must not point to the running JAR.
- The current JAR path can be identified and read. Platforms that replace it directly must also be able to overwrite it.
- The proxy/backend channel is healthy.

If automatic updating fails, preserve the logs and use a manual upgrade, still downloading from the official release.

Background download or installation failures log the full exception stack trace to the console. Spigot commands and backend update replies also include the exception type to help distinguish file locks, permissions, path issues, and network errors. Cleanup failures name the affected file in the log; it can be removed manually while the server is stopped.

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
