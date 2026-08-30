# 代理与后端子服

本模式用于让 BungeeCord 或 Velocity 接收 QQ 指令，并在指定 Spigot 子服执行 TPS 查询或快捷命令。普通群聊转发不需要配置该通道。

## 部署要求

- 代理安装 `ShitBotBungee-*.jar` 或 `ShitBotVelocity-*.jar`；
- 每个目标 Bukkit 子服安装 `ShitBotSpigot-*.jar`；
- 代理连接 OneBot；
- 后端 Spigot 使用 `deployment.role: "backend"`，不连接 OneBot；
- 所有实例连接同一个 MySQL 数据库；
- 每个后端使用独立的名称、端口和至少 16 位随机密钥。

不要在代理和后端之间共享 SQLite 文件，也不要把后端监听端口直接暴露到公网。

## 1. 配置后端 Spigot

编辑后端的 `config.yml`：

```yaml
deployment:
  role: "backend"

database:
  type: "mysql"
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "shitbot"
    username: "shitbot"
    password: "替换为数据库密码"
```

编辑后端的 `commands.yml`：

```yaml
backend-transport:
  listener:
    enabled: true
    bind-address: "127.0.0.1"
    port: 25580
    token: "替换成至少16位的强随机密钥"
    server-name: "survival"
    allowed-proxy-addresses:
      - "127.0.0.1"
      - "::1"
```

`server-name` 必须与代理 `endpoints` 中的键完全一致。

## 2. 配置代理

代理的 `config.yml` 使用与后端完全相同的 MySQL 数据库，并正常配置 OneBot。

编辑代理的 `commands.yml`：

```yaml
backend-transport:
  default-server: "survival"
  endpoints:
    survival:
      host: "127.0.0.1"
      port: 25580
      token: "替换成与后端相同的强随机密钥"
      allow-insecure-remote-plaintext: false
      tls:
        enabled: false
```

多个后端分别添加：

```yaml
backend-transport:
  endpoints:
    survival:
      host: "127.0.0.1"
      port: 25580
      token: "survival的随机密钥"
    lobby:
      host: "127.0.0.1"
      port: 25581
      token: "lobby的随机密钥"
```

只有一个 endpoint 且 `default-server` 为空时，会自动选择该后端。有多个 endpoint 时建议明确设置 `default-server`，或要求群成员在命令后指定子服。

## 3. 同机部署

代理和后端在同一台机器时，建议保持：

- 后端 `bind-address: "127.0.0.1"`；
- 代理 endpoint 使用 `host: "127.0.0.1"`；
- 后端只允许 `127.0.0.1` 和 `::1`；
- `allow-insecure-remote-plaintext: false`；
- TLS 可以关闭。

回环地址上的明文通道不会触发远程明文拒绝规则。

## 4. 跨机器部署

跨机器时默认拒绝明文连接。应配置 TLS，并通过防火墙只允许代理服务器访问后端端口。

后端 `commands.yml`：

```yaml
backend-transport:
  listener:
    enabled: true
    bind-address: "0.0.0.0"
    port: 25580
    token: "替换成至少16位的强随机密钥"
    server-name: "survival"
    allowed-proxy-addresses:
      - "代理服务器的实际IP"
    tls:
      enabled: true
      key-store: "backend-server.p12"
      key-store-password: "替换为密码"
      trust-store: ""
      trust-store-password: ""
      require-client-certificate: false
```

代理 `commands.yml`：

```yaml
backend-transport:
  endpoints:
    survival:
      host: "后端服务器域名或IP"
      port: 25580
      token: "与后端相同的随机密钥"
      allow-insecure-remote-plaintext: false
      tls:
        enabled: true
        trust-store: "backend-trust.p12"
        trust-store-password: "替换为密码"
        key-store: ""
        key-store-password: ""
```

PKCS12 文件路径相对于各自的插件数据目录。使用公共 CA 证书且 JVM 已信任该 CA 时，代理 `trust-store` 可以留空。

需要双向 TLS 时：

1. 后端设置 `require-client-certificate: true` 并配置用于信任客户端证书的 `trust-store`；
2. 代理配置自己的 `key-store` 和密码；
3. 保留 HMAC `token`，TLS 不替代应用层请求认证。

只有链路已经运行在 WireGuard、Tailscale 等可信加密隧道中，并且明确接受风险时，才考虑启用 `allow-insecure-remote-plaintext`。

## 5. 启动与检查

建议顺序：

1. 启动 MySQL；
2. 启动所有后端 Spigot；
3. 启动 BungeeCord 或 Velocity；
4. 在各端执行 `/shitbot status`；
5. 在 QQ 群执行 `TPS survival`；
6. 再执行一个权限受控的快捷命令。

如果代理无法连接后端，检查地址、端口、Token、`server-name`、来源 IP、TLS 证书、系统时间和防火墙。

## 命令目标选择

```text
TPS survival
lp编辑 survival
```

命令末尾的子服名优先于 `tps.server`、快捷命令的 `server` 和 `default-server`。请求只会选择一个后端，不会广播到全部子服。
