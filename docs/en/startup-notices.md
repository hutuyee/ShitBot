# Server startup notices

[简体中文](../startup-notices.md) | **English**

Administrators can announce this instance's startup or have a proxy wait for a specific backend. Notices go only to groups explicitly listed in `onebot.allowed-group-ids`. Setting `allow-all-groups: true` does not expand the notice audience.

## Announce proxy startup

In BungeeCord or Velocity's `config.yml`:

```yaml
onebot:
  enabled: true
  allowed-group-ids:
    - 123456789
  notices:
    server-startup:
      enabled: true
      target-server: ""
      check-interval-seconds: 5
```

Leave `target-server` empty. A notice is sent once the ShitBot runtime is ready and OneBot connects. Spigot standalone and Nukkit-MOT use this configuration too, with notification work handed back to the platform thread. Spigot backends neither connect to OneBot nor send notices themselves.

## Wait for a specific backend

Configure this only on BungeeCord/Velocity:

```yaml
onebot:
  notices:
    server-startup:
      enabled: true
      target-server: "survival"
      check-interval-seconds: 5
```

`survival` must be a backend name in the proxy configuration. Every 5 seconds, the proxy attempts a Minecraft status ping. The first valid response stops polling and triggers the notice. The backend may already be online or start later. An unknown backend is reported in the log. Spigot and Nukkit reject nonempty `target-server` values.

Here, “online” means the backend answers a status ping. It does not guarantee that every plugin, world pregeneration task, or application initialization has finished. This is one notice per proxy startup; later backend restarts do not trigger it again.

## Notice text and reloads

Edit the active language file, `lang/<language>.yml`:

```yaml
notices:
  server-startup: "Server %server% has started. You can join now!"
```

`%server%` is the target backend name or current platform name. `%platform%` is the platform running ShitBot.

`/shitbot reload` does not resend a completed notice. If the target is still offline, the new configuration starts waiting again. If the notice was triggered while OneBot was disconnected, or some groups failed, pending state and the list of successful groups carry over to the new runtime. Unconfirmed groups are retried after reconnection. A network interruption can make the send result uncertain, so retries may duplicate notices; strict exactly-once delivery across network failures is not guaranteed. State exists only within the current plugin process. A normal restart begins a new startup notice cycle.
