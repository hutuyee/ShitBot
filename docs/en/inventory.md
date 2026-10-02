# Inventory queries and textures

[简体中文](../inventory.md) | **English**

## Enable inventory queries

Check these settings in `config.yml`:

```yaml
onebot:
  commands:
    inventory:
      enabled: true

inventory:
  enabled: true
  template: "default"
```

Save and run `/shitbot reload`. Spigot and Nukkit-MOT save online inventories periodically and again when players leave.

Edit aliases and replies under `commands.inventory` and `messages` in the selected `lang/*.yml`. Edit image appearance under `inventory` in `templates/*.yml`; copy the default theme and select it with `inventory.template`. See [Configuration](configuration.md#lightweight-theme-files).

## Usage

With `language: "en_US"`, group members send `inventory` or `my inventory` to select a character bound to their QQ automatically. Aliases can be changed in the language file.

For multiple Minecraft IDs, use `inventory <Minecraft ID>` or `my inventory <Minecraft ID>`. The exact ID must belong to the sender's QQ before the snapshot is read and rendered. Other users' characters cannot be queried. The corresponding Chinese aliases are `背包` and `我的背包`.

## External inventory template

Enabling advanced templates creates `image-templates/inventory/`. Use `/shitbot editor` to edit equipment, storage, hotbar, counts, and durability layers. Set the preview context's `player` to an actual player name, then save and publish changes.

Set `image-templates.commands.inventory.enabled` to `true` in `commands.yml` and reload to query a bound character with `custom inventory`. This entry uses the same snapshots, binding checks, and texture settings, and requires `inventory.enabled: true`. The original `inventory` command retains its native image. See [Profile and inventory templates](image-templates.md#profile-and-inventory-templates) for activation and data fields.

## Offline queries

Online snapshots are saved every 60 seconds by default and immediately on logout. They are retained for 30 days by default, so QQ users can query the latest inventory while offline.

If the player has never joined a server that saves snapshots, the snapshot expired, or `inventory.enabled` is off, the bot reports that no snapshot is available. New fields missing from older snapshot formats require the player to join again.

## Server networks

Proxies do not have live inventories. Connect the proxy and all Spigot backends to the same MySQL database:

- Backends can disable `onebot.enabled` while keeping `inventory.enabled` to save periodic and logout snapshots.
- BungeeCord/Velocity receives QQ commands, reads shared snapshots, and renders images.
- SQLite is for a single server and cannot serve as a shared multiprocess database.

The rendering proxy also needs the same item resources. Put client JARs, packs, and mod JARs where the proxy can read them and use absolute paths in `resource-archives`, or copy precisely exported `item-icons`.

## Texture resolution across versions

Resolution order:

1. `item-icons/<namespace>/<path>__cmd_<custom-model-data>.png`.
2. `item-icons/<namespace>/<path>__data_<legacy-data-value>.png`.
3. `item-icons/<namespace>/<path>.png`.
4. Directories, resource packs, client JARs, or resource JARs explicitly listed in `inventory.icons.resource-archives`.
5. Automatically discovered `resources`, `resourcepacks`, `server-resource-packs`, `mods`, `versions`, and client JARs.

ShitBot's Spigot compatibility starts at 1.8.8. Its texture resolver covers resource formats used from 1.8.8 through the documented 26.x line, including direct texture directories from older packs:

- Legacy `textures/items` and `textures/blocks`.
- JSON item/block models from 1.8 onward, including `builtin/generated` and `builtin/handheld`.
- Bukkit material names, legacy registry names, and data variants from 1.8–1.12.
- Namespaced item IDs after the 1.13 flattening.
- `custom_model_data`, `damage`, and `damaged` in traditional model `overrides`.
- 1.21.4–26.x `assets/<namespace>/items/*.json`: model, composite, condition, range_dispatch, select, and basic special models.
- Bounded snapshots of modern CustomModelData floats, flags, strings, and colors.
- Player head textures through GameProfile, PlayerProfile, and ResolvableProfile from 1.8 onward.

Ordinary 2D items combine all `layer0...layer15`. Ordinary block models generate isometric icons from inherited top/side/face textures. Animated PNGs use their first frame, so the animation strip is not compressed into one slot.

### Where to put the client JAR

ShitBot does not include Mojang's vanilla textures. The instance rendering images must read the matching client JAR or a complete vanilla resource pack.

Default standalone layout:

```text
server/
├─ mods/
│  └─ 1.8.9-client.jar
└─ plugins/ShitBot/
```

The default `mods-directory: ../../mods` resolves from `plugins/ShitBot` to `server/mods`. The file must be a client JAR containing `assets/minecraft`. Forge/OptiFine installers, launcher components, or trimmed archives containing only classes cannot substitute for client resources.

For explicit priority:

```yaml
inventory:
  icons:
    resource-archives:
      - "D:/Minecraft/versions/1.8.9/1.8.9.jar"
      - "D:/Minecraft/resourcepacks/server-pack.zip"
```

The resource index builds asynchronously at startup. The first query waits at most `index-wait-ms`. File timestamps and sizes are checked every `refresh-seconds`; negative caching does not permanently hide textures after adding or replacing a JAR.

### Player heads

Spigot backends read `textures` from legacy CraftMetaSkull/GameProfile and modern PlayerProfile/ResolvableProfile. Only the hexadecimal content hash for `textures.minecraft.net` is saved. The renderer downloads skins from that fixed Mojang domain, combines the base head and hat layer, and caches them under:

```text
plugins/ShitBot/inventory-head-cache/
```

Cache cleanup runs asynchronously at startup, retaining at most 4,096 files or 64 MiB. If the server cannot reach `textures.minecraft.net`, it temporarily uses the resource pack's generic player head and retries after 30 seconds without blocking Bukkit's main thread.

Old format 1 and 2 offline snapshots have no head texture hash. After upgrading, have the character join and wait for a periodic snapshot, or query while online. A new format 3 snapshot is required for custom heads.

## Mod and custom renderer limits

Forge, NeoForge, and common hybrid servers attempt to reflect the actual `modid:item_name` from NMS/loader registries and read the mod JAR's `assets`. Ordinary JSON models can display automatically.

A generic server resolver cannot fully reproduce appearances generated by arbitrary client code or live context from resource files alone:

- Custom TESR/BEWLR, ISTER, or Fabric BuiltinItemRenderer implementations.
- Models changing with full NBT, capabilities, fluids, energy, or client state.
- Maps, banners, shield patterns, and other models needing world or complete pattern context.
- Custom OBJ/GLTF loaders and shader effects.

Override these with PNGs exported from actual client rendering:

```text
plugins/ShitBot/item-icons/
├─ minecraft/diamond.png
├─ create/precision_mechanism.png
├─ example/custom_item__cmd_10001.png
└─ minecraft/wool__data_14.png
```

Only items without an available icon show the question-mark placeholder. Slots, counts, and durability remain visible.

## Performance

- The default 60-second standalone snapshot interval usually needs no reduction.
- Large networks can use 4–8 MySQL `database.async-threads` and set `database.maximum-queued-tasks` to an acceptable backlog.
- Keep `render.maximum-concurrent` around 2–4; `render.maximum-queued` limits pending queries.
- Resource indexing has a separate thread, avoiding deadlocks with PNG rendering workers.
- Icon caches include a resource index generation; resource updates invalidate old results.
- Missing icons are negatively cached for only 30 seconds.
- Icon, snapshot, and rendering caches are bounded.
- Inventory fields are copied only on a safe thread: Bukkit's main thread or the player's region thread on Folia. Compression, SQL, resource scanning, and rendering run asynchronously.
