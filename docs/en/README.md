# ShitBot documentation

[简体中文](../README.md) | **English**

## Installation and everyday use

- [Installation and deployment](installation.md): platform JARs, first startup, and deployment options.
- [Configuration](configuration.md): key settings in `config.yml` and `commands.yml`.
- [Commands and permissions](commands.md): admin commands, QQ commands, shortcuts, and permission checks.
- [Server startup notices](startup-notices.md): proxy startup, waiting for a backend, reconnection retries, and reloads.
- [PlaceholderAPI variables](placeholders.md): status/binding placeholders and PAPI data in images.
- [Custom backgrounds and pixel coordinates](pixel-templates.md): prepare a PNG background and position variables.
- [Image rendering and advanced templates](image-templates.md): built-in and advanced modes, optional components, scenes, the editor, and the plugin API.
- [Troubleshooting](troubleshooting.md): connections, forwarding, binding, databases, and proxy commands.

## Server networks and data

- [Proxies and backend servers](proxy-backend.md): the command channel between BungeeCord/Velocity and Spigot backends.
- [Databases and migration](database.md): SQLite, MySQL, platform migration, and EasyBot imports.
- [Inventory queries and textures](inventory.md): offline snapshots, resource packs, mod items, and custom icons.

## Maintenance and development

- [Plugin API and whitelist management](api.md): asynchronous binding APIs, admin commands, and whitelist entries without QQ.
- [Platform compatibility](compatibility.md): recorded environments, platform differences, and optional dependencies.
- [Upgrading and automatic updates](updating.md): manual upgrades, `/shitbot update`, and release signatures.
- [Production security checklist](security.md): OneBot, databases, command transport, permissions, and logs.
- [Building and development](development.md): Maven builds, modules, and release artifacts.

For a first installation, read [Installation and deployment](installation.md), then [Configuration](configuration.md). Configure [Proxies and backend servers](proxy-backend.md) only if you need QQ commands to run on backends.

QQ command examples in these English pages use the built-in `en_US` aliases unless stated otherwise. Set `language: "en_US"` in `config.yml` and reload to use them. Changing the documentation language does not change the plugin's configuration.
