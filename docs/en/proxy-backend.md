# Proxies and backend servers

[简体中文](../proxy-backend.md) | **English**

This mode lets BungeeCord or Velocity receive QQ commands and send TPS queries, permission checks, shortcuts, or PlaceholderAPI queries to a specified Spigot backend. Ordinary chat forwarding does not need this channel.

## Requirements

- Install `ShitBotBungee-*.jar` or `ShitBotVelocity-*.jar` on the proxy.
- Install `ShitBotSpigot-*.jar` on each target Bukkit backend.
- Connect the proxy to OneBot.
- Set backend Spigot instances to `deployment.role: "backend"`; they do not connect to OneBot.
- Connect every instance to the same MySQL database.
- Give each backend a separate name, port, and random token of at least 16 characters.

Do not share SQLite files between proxies and backends or expose backend listener ports directly to the Internet.

## 1. Configure the Spigot backend

Backend `config.yml`:

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
    password: "REPLACE_WITH_DATABASE_PASSWORD"
```

Backend `commands.yml`:

```yaml
backend-transport:
  listener:
    enabled: true
    bind-address: "127.0.0.1"
    port: 25580
    token: "REPLACE_WITH_A_STRONG_RANDOM_TOKEN_OF_AT_LEAST_16_CHARACTERS"
    server-name: "survival"
    allowed-proxy-addresses:
      - "127.0.0.1"
      - "::1"
```

`server-name` must exactly match the key under the proxy's `endpoints`.

## 2. Configure the proxy

Connect the proxy's `config.yml` to exactly the same MySQL database, and configure OneBot normally.

Proxy `commands.yml`:

```yaml
backend-transport:
  default-server: "survival"
  endpoints:
    survival:
      host: "127.0.0.1"
      port: 25580
      token: "REPLACE_WITH_THE_SAME_STRONG_RANDOM_TOKEN_AS_THE_BACKEND"
      allow-insecure-remote-plaintext: false
      tls:
        enabled: false
```

For multiple backends:

```yaml
backend-transport:
  endpoints:
    survival:
      host: "127.0.0.1"
      port: 25580
      token: "REPLACE_WITH_SURVIVAL_RANDOM_TOKEN"
    lobby:
      host: "127.0.0.1"
      port: 25581
      token: "REPLACE_WITH_LOBBY_RANDOM_TOKEN"
```

With one endpoint and an empty `default-server`, that backend is selected automatically. With multiple endpoints, set `default-server` explicitly or have group members append the backend name to their command.

## 3. Same-machine deployment

When the proxy and backend share a machine, use:

- Backend `bind-address: "127.0.0.1"`.
- Proxy endpoint `host: "127.0.0.1"`.
- Only `127.0.0.1` and `::1` in the backend allowlist.
- `allow-insecure-remote-plaintext: false`.
- TLS may be disabled.

Plaintext loopback traffic does not trigger the remote plaintext rejection rule.

## 4. Deployment across machines

Remote plaintext is rejected by default. Configure TLS and allow only the proxy to reach the backend port through the firewall.

Backend `commands.yml`:

```yaml
backend-transport:
  listener:
    enabled: true
    bind-address: "0.0.0.0"
    port: 25580
    token: "REPLACE_WITH_A_STRONG_RANDOM_TOKEN_OF_AT_LEAST_16_CHARACTERS"
    server-name: "survival"
    allowed-proxy-addresses:
      - "REPLACE_WITH_ACTUAL_PROXY_IP"
    tls:
      enabled: true
      key-store: "backend-server.p12"
      key-store-password: "REPLACE_WITH_PASSWORD"
      trust-store: ""
      trust-store-password: ""
      require-client-certificate: false
```

Proxy `commands.yml`:

```yaml
backend-transport:
  endpoints:
    survival:
      host: "REPLACE_WITH_BACKEND_HOSTNAME_OR_IP"
      port: 25580
      token: "REPLACE_WITH_THE_SAME_RANDOM_TOKEN_AS_THE_BACKEND"
      allow-insecure-remote-plaintext: false
      tls:
        enabled: true
        trust-store: "backend-trust.p12"
        trust-store-password: "REPLACE_WITH_PASSWORD"
        key-store: ""
        key-store-password: ""
```

PKCS12 paths are relative to each instance's plugin data directory. The proxy's `trust-store` may be empty when using a public CA already trusted by the JVM.

For mutual TLS:

1. Set `require-client-certificate: true` on the backend and configure a `trust-store` that trusts the client certificate.
2. Configure the proxy's own `key-store` and password.
3. Keep the HMAC `token`; TLS does not replace application-level request authentication.

Consider `allow-insecure-remote-plaintext` only when the channel already runs inside a trusted encrypted tunnel such as WireGuard or Tailscale and you explicitly accept the risk.

## 5. Startup and checks

Suggested order:

1. Start MySQL.
2. Start all Spigot backends.
3. Start BungeeCord or Velocity.
4. Run `/shitbot status` on each instance.
5. Send `TPS survival` in QQ.
6. Run a shortcut protected by a permission.

If the proxy cannot connect, check the address, port, token, `server-name`, source IP, TLS certificates, system clocks, and firewall.

When an advanced template declares `papi` in its manifest, the proxy uses the same channel for batched queries to the target Spigot backend. Both sides must use the same ShitBot version, and the backend must have PlaceholderAPI installed and enabled. The template's server setting or the command's `target-server` must match the endpoint name. PAPI queries do not fall back to local proxy resolution.

## Command target selection

With `language: "en_US"`:

```text
TPS survival
lp editor survival
```

The backend appended to the command takes precedence over `tps.server`, the shortcut's `server`, and `default-server`. Each request selects one backend; it is not broadcast to all backends.
