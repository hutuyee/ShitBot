# 数据库与数据迁移

ShitBot 支持 SQLite 和 MySQL，所有平台使用相同的数据表结构。

## 选择数据库

| 部署方式 | 建议 |
| --- | --- |
| 单个 Spigot/Paper/Folia 服务端 | SQLite 或 MySQL |
| 单个 Nukkit-MOT 服务端 | SQLite 或 MySQL |
| 只在一个代理实例运行 ShitBot | SQLite 或 MySQL |
| 代理与一个或多个 Spigot 后端 | 必须使用同一个 MySQL 数据库 |
| 多个独立 ShitBot 实例共享绑定或背包数据 | 必须使用 MySQL |

SQLite 文件不能由多个进程同时共享。不要把同一个 `shitbot.db` 放在网络盘上供多个实例打开。

## SQLite

```yaml
database:
  type: "sqlite"
  sqlite:
    file: "shitbot.db"
```

数据库文件位于插件数据目录。单实例使用时不需要额外数据库服务。

## 本机 MySQL

```yaml
database:
  type: "mysql"
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "shitbot"
    username: "shitbot"
    password: "替换为数据库密码"
    parameters: "useUnicode=true&characterEncoding=utf8&sslMode=DISABLED&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=false"
    allow-insecure-remote-mysql: false
```

建议为 ShitBot 创建独立数据库和账号，只授予该数据库所需权限。

## 远程 MySQL

远程 MySQL 默认必须使用 TLS。推荐参数：

```yaml
database:
  type: "mysql"
  mysql:
    host: "mysql.example.com"
    port: 3306
    database: "shitbot"
    username: "shitbot"
    password: "替换为数据库密码"
    parameters: "useUnicode=true&characterEncoding=utf8&sslMode=VERIFY_IDENTITY&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=false"
    allow-insecure-remote-mysql: false
```

`VERIFY_IDENTITY` 会同时验证证书和主机名。确保 Java 信任签发 MySQL 证书的 CA，并使用证书中包含的主机名连接。

`allow-insecure-remote-mysql: true` 会允许远程明文连接，只应在已有可信加密隧道且确认风险后使用。

## 连接池

默认连接池适合一般服务器：

- SQLite 会自动使用单个异步数据库线程；
- MySQL 可以按负载调整 `pool.maximum-pool-size` 与 `async-threads`；
- `maximum-queued-tasks` 限制排队任务，队列满时新任务会快速失败；
- 连接、校验、Socket 和生命周期均有超时，避免无限等待。

大型群组服可以从 `async-threads: 4` 开始观察，再根据数据库负载调整。不要简单地把连接池和队列设置得很大。

## JDBC 驱动

ShitBot 会优先复用运行环境提供的 JDBC 驱动。缺少所需驱动时，会从 `repo.maven.apache.org` 下载固定版本、校验大小和 SHA-256，并缓存在插件数据目录的 `libraries/`。

首次连接 MySQL 时，应确保服务器可以访问 Maven Central，或提前让运行环境提供兼容的 MySQL Connector/J。

## 在平台之间迁移

### MySQL

四个平台使用相同表结构。迁移步骤：

1. 停止旧实例；
2. 备份数据库；
3. 将新平台插件连接到原 MySQL；
4. 保持数据库名和账号配置一致；
5. 启动新实例并检查数据库迁移日志；
6. 执行 `/shitbot status`。

同一时间只运行计划中的实例。ShitBot 会使用 MySQL 数据库锁协调 schema 迁移，但部署切换仍应避免旧实例继续处理业务。

### SQLite

1. 停止原实例；
2. 备份插件数据目录；
3. 找到 `shitbot.db`；
4. 将它复制到新平台的插件数据目录；
5. 保持 `database.type: "sqlite"` 和文件名一致；
6. 启动新实例并检查日志。

不要在服务器运行期间直接复制 SQLite 文件。

## 从 EasyBot 迁移绑定

1. 备份当前 ShitBot 数据库；
2. 将 EasyBot 数据库放入 ShitBot 插件数据目录；
3. 确认 `binding.maximum-ids-per-qq` 足以容纳现有多角色绑定；
4. 执行：

```text
/shitbot migrate easybot EasyBot.db
```

文件名省略时默认使用 `EasyBot.db`：

```text
/shitbot migrate easybot
```

迁移前会统计每个 QQ 的绑定数量。如果超过 `maximum-ids-per-qq`，整次迁移会取消，不会只导入一部分。

## 备份建议

- 升级插件或切换平台前备份数据库；
- MySQL 使用数据库自身的一致性备份工具；
- SQLite 必须停服后复制；
- 同时保存当前 `config.yml` 和 `commands.yml`；
- 不要把包含密码、Token 或玩家数据的备份提交到 Git。
