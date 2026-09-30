# Databases and migration

[简体中文](../database.md) | **English**

ShitBot supports SQLite and MySQL. All platforms share the same table schema. Schema version 7 adds `shitbot_player_stats` for persisted personal-profile online time.

## Choose a database

| Deployment | Recommendation |
| --- | --- |
| One Spigot/Paper/Folia server | SQLite or MySQL |
| One Nukkit-MOT server | SQLite or MySQL |
| ShitBot on a single proxy only | SQLite or MySQL |
| Proxy with one or more Spigot backends | The same MySQL database is required |
| Independent instances sharing bindings or inventories | MySQL is required |

Do not share a SQLite file between processes or place one `shitbot.db` on a network drive for multiple instances to open.

## SQLite

```yaml
database:
  type: "sqlite"
  sqlite:
    file: "shitbot.db"
```

The file is stored in the plugin data directory. A single instance needs no separate database service.

## Local MySQL

```yaml
database:
  type: "mysql"
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "shitbot"
    username: "shitbot"
    password: "REPLACE_WITH_DATABASE_PASSWORD"
    parameters: "useUnicode=true&characterEncoding=utf8&sslMode=DISABLED&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=false"
    allow-insecure-remote-mysql: false
```

Create a dedicated database and account for ShitBot and grant only the permissions required on that database.

## Remote MySQL

Remote MySQL requires TLS by default. Recommended parameters:

```yaml
database:
  type: "mysql"
  mysql:
    host: "mysql.example.com"
    port: 3306
    database: "shitbot"
    username: "shitbot"
    password: "REPLACE_WITH_DATABASE_PASSWORD"
    parameters: "useUnicode=true&characterEncoding=utf8&sslMode=VERIFY_IDENTITY&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=false"
    allow-insecure-remote-mysql: false
```

`VERIFY_IDENTITY` checks both the certificate and hostname. Ensure Java trusts the issuing CA and connect with a hostname included in the certificate.

`allow-insecure-remote-mysql: true` allows remote plaintext. Use it only with an existing trusted encrypted tunnel and after accepting the risk.

## Connection pool

Defaults suit typical servers:

- SQLite automatically uses one asynchronous database thread.
- MySQL can tune `pool.maximum-pool-size` and `async-threads` for load.
- `maximum-queued-tasks` bounds queued work; new tasks fail quickly when full.
- Connection, validation, socket, and lifetime timeouts prevent indefinite waits.

Large networks can start with `async-threads: 4`, observe database load, and adjust. Do not simply increase pool and queue sizes without bounds.

## JDBC drivers

ShitBot first reuses JDBC drivers supplied by the runtime. If a required driver is missing, it downloads a fixed version from `repo.maven.apache.org`, checks its size and SHA-256, and caches it under `libraries/` in the plugin data directory.

Before the first MySQL connection, ensure access to Maven Central or provide a compatible MySQL Connector/J through the runtime.

## Moving between platforms

### MySQL

All four platforms use the same schema:

1. Stop the old instance.
2. Back up the database.
3. Connect the new platform plugin to the existing MySQL database.
4. Keep database name and account settings consistent.
5. Start the new instance and inspect migration logs.
6. Run `/shitbot status`.

Run only the intended instances. ShitBot coordinates schema migrations with a MySQL database lock, but the old instance should still stop processing work during a deployment switch.

### SQLite

1. Stop the old instance.
2. Back up its plugin data directory.
3. Locate `shitbot.db`.
4. Copy it into the new platform's plugin data directory.
5. Keep `database.type: "sqlite"` and the filename consistent.
6. Start the new instance and inspect its logs.

Do not directly copy the SQLite file while the server is running.

## Importing EasyBot bindings

1. Back up the current ShitBot database.
2. Place the EasyBot database in ShitBot's data directory.
3. Ensure `binding.maximum-ids-per-qq` allows the existing number of characters.
4. Run:

```text
/shitbot migrate easybot EasyBot.db
```

If omitted, the filename defaults to `EasyBot.db`:

```text
/shitbot migrate easybot
```

Before importing, ShitBot counts bindings for each QQ account. If any exceed `maximum-ids-per-qq`, the entire import is canceled rather than partially importing data.

## Backups

- Back up the database before upgrades or platform changes.
- Use MySQL's consistent backup tools.
- Stop the server before copying SQLite.
- Save the current `config.yml` and `commands.yml` as well.
- Do not commit backups containing passwords, tokens, or player data to Git.
