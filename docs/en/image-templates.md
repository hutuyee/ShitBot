# Image rendering and advanced templates

[简体中文](../image-templates.md) | **English**

ShitBot offers two separate rendering paths. The default suits ordinary servers; advanced mode is for flexible layouts, previews with real data, and plugin API integration.

## Two modes

| Mode | Configuration | Runtime | Downloads | Suitable for |
| --- | --- | --- | --- | --- |
| Built-in Java images | `image.renderer: "java"` | Java2D online images already in the platform plugin, styled by `templates/*.yml` | No advanced component | Low overhead and simple configuration |
| Advanced scene templates | `custom-image-templates.enabled: true` | Separate ShitBotRenderer, `image-templates/` scenes, providers, and optional editor | Normal mode downloads if no valid cache exists; debug mode loads only a local JAR | Free layouts, conditions/loops, PAPI, or third-party plugin calls |

The advanced renderer also uses Java2D. It contains no Chromium, Node.js, or React runtime and does not execute uploaded HTML, JavaScript, or server commands. Platform and renderer JARs are published separately. Template images and resources live only in the plugin data directory.

### Use only built-in images

Keep the defaults:

```yaml
image:
  renderer: "java"
  template: "default"

custom-image-templates:
  enabled: false
```

No advanced engine or editor starts, and no component download address is accessed. Online images continue reading `templates/<name>.yml`; inventory images also use this lightweight theme format.

### Built-in appearance and background caching

New installations use a muted dark blue-gray and mint theme. Online and inventory images share gradient cards, soft shadows, fine borders, and status colors. Online images use equal-width player cards; the actual name widths and `image.players-per-row` limit determine columns. Title, total online count, backend counts, and time are laid out separately. Nearest-neighbor avatar scaling preserves Minecraft's pixel edges.

Inventory images separate the main inventory, hotbar, and equipment. Hotbar slots show numbers 1–9, counts have separate badges, enchanted items have subtle highlights, and durability bars have rounded corners. `inventory.layout.hotbar-gap` controls extra spacing before the hotbar. The summary and snapshot time wrap to two lines if necessary, and long names are truncated to fit.

Layout and drawing improvements also apply to existing themes. Existing `templates/default.yml` colors and custom values are preserved. To adopt the complete new default theme after upgrading, back up the file, replace it with the new version's resource, and run `/shitbot reload`.

At startup and reload, image workers pre-render static backgrounds for online and inventory images. Each later render copies its background before inserting counts, avatars, names, items, durability, and time. Dynamic data is never written back to the background, so removed players or items leave no stale content.

Online background cache keys include backend names, panel height, and card layout. A change in player count or name width that affects layout creates a new background. Inventory backgrounds reuse fixed slots and equipment labels. Each image type's in-memory background cache holds at most 4 entries totaling 8,388,608 pixels. Larger images can still render, but their backgrounds are not retained. Reloads and restarts create fresh caches with current themes, language, and sizes. Final PNG caching still follows `image.cache-seconds` and the inventory rendering cache settings.

### Use an advanced template for the built-in status command

```yaml
image:
  renderer: "custom"
  custom-template: "online-status"

custom-image-templates:
  enabled: true
```

`image.renderer: "custom"` with `custom-image-templates.enabled: false` is invalid and is explicitly rejected instead of silently falling back.

You can also keep `image.renderer: "java"` and enable only `custom-image-templates.enabled`. The default online image then stays on the lightweight path, while the editor, custom QQ commands, and other plugins using ShitBotApi can use advanced templates.

## Downloads and caching

The download logic never runs while advanced templates are disabled. When enabled:

1. Read `custom-image-templates.component.version`; an empty value uses the platform plugin's version.
2. With `debug: true`, load only `components/image-renderer/<version>/ShitBotRenderer-<version>.jar` in the data directory, skipping checksum and signature checks. Missing or invalid files fail without downloading.
3. With `debug: false`, inspect the cache under `components/image-renderer/<version>/`.
4. Normal mode verifies JAR size, release SHA-256, detached RSA signature, embedded component version, and service entry point.
5. Only if the cache is missing or invalid, download the JAR, `.sha256`, and `.sig` from the official ShitBot release.
6. Start the component with an isolated class loader after all checks pass.

Defaults:

```yaml
custom-image-templates:
  enabled: false
  directory: "image-templates"
  component:
    version: ""
    download-url: "https://github.com/hutuyee/ShitBot/releases/download/%version%/ShitBotRenderer-%version%.jar"
    maximum-download-bytes: 4194304
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
```

Normal downloads accept only official ShitBot release HTTPS URLs and GitHub release-asset redirects. Corrupted cached JARs are not loaded. The release must include the matching renderer, checksum, and signature. Otherwise advanced startup fails, while built-in Java images remain usable after disabling the advanced system.

A debug-mode local JAR is still checked for size and must contain `META-INF/services/haaa.shitbot.api.spi.ImageTemplateEngineFactory` and a matching `META-INF/shitbot-renderer.version`. Adjacent `.sha256` and `.sig` files and public key verification are not used. If missing, the error gives the full expected path.

## Template directory

When the advanced component starts or reloads with the plugin, it completes the built-in `online-status` template, even if other templates already exist. A newly created example is published:

```text
image-templates/
└─ online-status/
   ├─ manifest.yml
   ├─ scene.yml
   ├─ assets/
   ├─ published.yml
   └─ versions/
      └─ 1/
         ├─ manifest.yml
         ├─ scene.yml
         └─ assets/
```

- Root `manifest.yml`, `scene.yml`, and `assets/` form the draft.
- `versions/<number>/` contains a production snapshot copied at publication.
- `published.yml` selects the active version and records snapshot SHA-256 values.
- Normal rendering reads and verifies published snapshots, not the draft being edited.
- Rollback only changes the production pointer; it does not overwrite the draft or history.

Do not edit `versions/` or `published.yml` manually. Edit the draft and publish a new version.

Missing `manifest.yml`, `scene.yml`, or the `assets/` directory in the `online-status` draft are restored automatically. Empty YAML files, missing keys, and `null` values are completed from the bundled template and saved. Existing canvas settings, layer lists, and provider lists are retained; lists are never merged by position. Completing an existing draft does not alter historical snapshots or publish a new version. Publish through the editor to apply it to an existing production template. If `published.yml` is missing but historical versions remain, its pointer is restored from the newest historical version, which must pass template validation.

The default `online-status` is a 1200 × 960 white/gray card. It uses a solid light gray `#F3F4F6` background, an opaque white main card, dark gray `#20262E` titles and names, and `#59636E` hints and footer text. Small areas of blue-gray `#355874` and pale blue-gray `#EDF2F6` emphasize the total online count, replacing pink/purple gradients and large decorative color blocks. The top shows the title, configured server name, and total count. Standalone Bukkit/Spigot and Nukkit servers lay out player cards directly, without implementation names such as CraftBukkit or backend group frames. BungeeCord/Velocity obtains each player's server ID from the proxy snapshot and arranges backend panels in two columns, retaining IDs even with one backend. Each player has a rounded-square pixel avatar and name. The footer shows only ShitBot branding and generation time. Avatars use `${player.avatar}` from `online-players`, following `image.avatar.url-template`. With `image.avatar.enabled: false`, player avatars are not requested.

The colors reference WCAG 2.2 [text contrast requirements](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html): at least 4.5:1 for normal text and 3:1 for large text. Using the template's sRGB values, secondary text on white is approximately 6.11:1, backend counts on light gray labels 5.45:1, and the total count and its label 6.66:1. Count indicators on white exceed the [3:1 requirement for meaningful graphics](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) and always accompany count text to avoid [using color alone](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html). Large white/gray areas with small accents are a visual choice, not a WCAG rule about color proportions. These calculations are not a full accessibility assessment.

Standalone layouts show up to 40 players in four columns. Proxy layouts show up to 4 backends with up to 8 players each. Counts always use the full online data, and overflow hints indicate omitted players/backends. A zero total shows a full-page empty state; empty proxy backends have their own hints. Both layouts fit the default layer and canvas limits.

Upgrades fill missing content in the built-in template draft while retaining existing values and layer lists. New templates created in the editor use the new defaults. To replace the entire layout of an existing `online-status`, copy the release source's `ShitBotRenderer/src/main/resources/defaults/online-status/scene.yml` into its scene draft, then save, preview, and publish through the editor.

Template IDs allow lowercase letters, digits, underscores, and hyphens, up to 64 characters. Files must remain within the plugin data directory. Absolute paths, traversal, and symlinks escaping the directory are rejected.

## manifest.yml

```yaml
schema-version: template-v1
id: player-card
name: Player card
suggested-file-name: player-card.png
providers:
  - id: shitbot
  - id: player-avatar
    player: "${context.player}"
  - id: papi
    player: "${context.player}"
    server: "${context.server}"
    placeholders:
      display_name: "%player_displayname%"
      health: "%player_health%"
```

Declare only data the template needs. Templates cannot dynamically call arbitrary Java classes, network APIs, or console commands. Data is first collected on safe platform threads or separate data workers, then passed to image workers as plain maps, lists, strings, and numbers.

Built-in providers:

| ID | Output | Content |
| --- | --- | --- |
| `shitbot` | `${data.shitbot.*}` | Platform, plugin version, and generation time |
| `online-players` | `${data.online-players.*}` | `total`, flat `players`, grouped `servers`, and the `group-by-server` flag; avatar URLs when enabled |
| `player-avatar` | `${data.player-avatar.*}` | Selected `player`, `url`, and service `url-template`; `template-only: true` supplies only the URL template for independently bound avatar layers |
| `papi` | `${data.papi.*}` | Explicitly declared PlaceholderAPI values, a `values` map, and per-item `errors` |
| `player-profile` | `${data.player-profile.*}` | The player's skin URL, optional permission group and points, persisted online seconds/formatted duration, and online state; select the player with `options.player` or context `player` |

`online-players.group-by-server` is `true` on BungeeCord/Velocity and `false` on standalone platforms. Proxy `servers[].id` is the server ID supplied by the proxy; `servers[].name` retains the existing name field. Groups also include `players` and `count`. Standalone templates can loop over `${data.online-players.players}` without group headings.

`custom-image-templates.remote-images.enabled` defaults to `true`, allowing player-avatar and other HTTPS URLs alongside local `assets/`. An explicit `false` in an old configuration remains effective; change it to `true` and reload for network avatars. HTTPS, address, response size, and pixel limits still apply.

## scene.yml

A minimal scene:

```yaml
schema-version: scene-v1
canvas:
  width: 900
  height: 500
  background: "#172033"
layers:
  - type: text
    x: 40
    y: 32
    width: 820
    text: "Hello ${context.player}"
    font-size: 36
    font-style: bold
    color: "#FFFFFFFF"
```

Colors support `#RRGGBB` and `#AARRGGBB`. Canvases also support `gradient-start` and `gradient-end`.

Ordinary layers support `x`, `y`, `width`, `height`, `opacity`, `visible`, and `when`. Main node types:

| Node | Common properties |
| --- | --- |
| `text` | `text`, `font-family`, `font-size`, `font-style`, `color`, `align`, `line-height`, `maximum-lines` |
| `image` | `source`, `fit: cover/contain/stretch`, `radius`, stroke; local assets use `assets/name.png` |
| `avatar` | `player` name or variable, direct `source`, `shape: square/circle`, `pixelated`, dimensions, radius, stroke; legacy layers without a shape remain circular |
| `rectangle` | `fill`, `radius`, `stroke-color`, `stroke-width` |
| `circle` | `fill`, `diameter` or dimensions, stroke |
| `line` | `x2`/`y2` or dimensions, `color`, `stroke-width` |
| `progress` | `value`, `maximum`, `background`, `fill`, `radius` |
| `group` | `children`; `clip: true` clips to group dimensions |
| `stack` | `children`, `direction: vertical/horizontal`, `gap` |
| `grid` | `children`, `columns`, `cell-width`, `cell-height`, row/column gaps |
| `condition` | `condition`, optional `equals`, `then`, `else` |
| `loop` | `items`, `as`, `maximum-items`, `children`; ordinary layouts can also use per-item offsets |

### Place player avatars

Select Player avatar (`玩家头像`) at the top of the editor and add a layer, then choose its source in the right panel:

- **Specified player/custom variable**: enter a player name or a variable such as `${player.name}`. Different avatar layers can use different players. New root-level avatars start with the sample name `Steve`.
- **Current player**: use `${context.player}` for profile cards or bound-player commands. Put `player` in the preview data's `context`; production callers supply it. Without a player value, the avatar is not drawn.
- **Player in the containing loop**: when added to a player loop or nested container, the avatar binds to that loop's name field, such as `${player.name}`. Loops over `server.players` are supported.
- **Image URL/avatar image variable**: enter `assets/head.png`, an HTTPS URL, or `${player.avatar}`. Existing direct-image avatar sources remain compatible.

Avatars support dragging, resizing, squares, rounded squares, and circles. New avatars use rounded squares and crisp pixel scaling by default; smooth scaling is optional. The canvas shows placement and a player marker. Use Preview (`预览`) to see real avatars.

Network avatars use `image.avatar.url-template`. Remote images are enabled in new configurations. If disabled in an older configuration, change this and reload:

```yaml
custom-image-templates:
  remote-images:
    enabled: true
```

Adding a player-name avatar in the editor automatically adds a `player-avatar` provider declaration and includes both changes in undo history. Existing declarations with a `player` are preserved. Shorthand declarations without options become template-only declarations. In handwritten manifests, add:

```yaml
providers:
  - id: player-avatar
    template-only: true
```

Then add an avatar to `scene.yml`:

```yaml
layers:
  - type: avatar
    name: Player avatar
    player: Steve  # Or ${context.player} / ${player.name} inside a player loop
    x: 40
    y: 40
    width: 64
    height: 64
    shape: square  # circle is round; square plus radius gives rounded corners
    radius: 8
    pixelated: true
```

`player` mode constructs a URL for the player bound to each layer and uses the existing image loader/cache. An explicit `source` or legacy `avatar` field takes precedence; avoid mixing it with `player`. Legacy declarations can still set `player: "${context.player}"` and read one avatar URL through `${data.player-avatar.url}`.

### Variable binding

- `${context.player}` reads caller context.
- `${data.papi.health}` reads provider data.
- An expression occupying the entire value retains its original type, so `${data.online-players.servers}` can be passed directly to `loop.items`.
- Expressions embedded in text become strings.
- Loop variables are roots: after `as: player`, use `${player.name}`.
- Loops also expose `${index}`, `${first}`, and `${last}`.

Put PAPI expressions only in the manifest's `papi.placeholders` declaration. Scenes read `${data.papi.<alias>}`; arbitrary scene text is not scanned for implicit PlaceholderAPI execution.

## PlaceholderAPI and proxy backends

Spigot/Paper/Folia resolves declared placeholders in batches on the correct thread for the player. Missing PlaceholderAPI, offline players, unresolved expressions, and timeouts return explicit errors. `maximum-queries`, `timeout-ms`, and `cache-seconds` limit query count, waiting, and short-term caching.

BungeeCord/Velocity cannot resolve Bukkit placeholders locally. The template's `papi.server` or command's `target-server` routes requests through the existing authenticated channel to a Spigot backend. Both sides must use the same ShitBot version. The backend needs PlaceholderAPI and a configured `backend-transport.listener`.

Nukkit-MOT does not emulate Bukkit PlaceholderAPI. It supports `shitbot`, `online-players`, `player-avatar`, and registered third-party providers; declaring `papi` returns an unsupported error.

## Custom QQ image commands

Templates do not register commands themselves. Configure each entry explicitly in `commands.yml`:

```yaml
image-templates:
  commands:
    player-card:
      enabled: true
      aliases:
        - "my card"
      template: "player-card"
      player-source: "bound"
      target-server: "survival"
      permission: "shitbot.image.player-card"
      cooldown-seconds: 10
      usage: "%at% Usage: my card [bound character]"
      failed: "%at% Image generation failed: %result%"
```

`player-source` options:

| Value | Behavior |
| --- | --- |
| `bound` | Use the sender's most recently bound character; remaining text goes into `${context.arguments}` |
| `argument` | Require an exact character name after the command and verify ownership by the sender |
| `none` | Do not read bindings; `${context.player}` is empty. A nonempty `permission` cannot pass without a character to check |

Available context includes `title`, `server-name`, `qq`, `group`, `sender`, `player`, `players`, `server`, `arguments`, `command`, and `platform`. Permission requests only check permissions and do not execute console commands. Built-in OneBot commands and existing console shortcuts match first, so use unique aliases.

## Visual editor

The editor is disabled by default. To enable it:

```yaml
custom-image-templates:
  editor:
    enabled: true
    bind-address: "127.0.0.1"
    port: 0
    login-seconds: 60
    maximum-upload-bytes: 2097152
```

After reload, an administrator with `shitbot.admin` runs:

```text
/shitbot editor
```

The command returns a short-lived, one-time login URL. `login-seconds` controls only the initial login window. After login, a reusable session supports saving, previewing, and publishing. The idle lifetime is 24 hours and every request renews it. An open page renews automatically every 5 minutes and immediately when returning to the tab, so prolonged canvas or YAML editing does not require saving to stay logged in. Renewal does not save or change the draft.

The editor currently has a Chinese dark interface: templates and layers on the left, canvas in the center, and design properties on the right. YAML, preview data, and rendered previews have separate editing areas. The Chinese button labels below refer to this interface.

Select an element on the canvas or layer list and press `Delete` or `Backspace` to remove it, including groups, stacks, grids, and their children. Deletion is immediate and undoable with `Ctrl+Z`. In text inputs, deletion, select all, copy, and undo retain normal text-editing behavior. Chinese IME composition does not trigger layer shortcuts.

| Action | Shortcut |
| --- | --- |
| Save draft | `Ctrl+S` |
| Delete selected layers/layouts | `Delete` / `Backspace` |
| Undo / redo | `Ctrl+Z` / `Ctrl+Shift+Z` or `Ctrl+Y` |
| Duplicate selected layers | `Ctrl+D` |
| Add/remove selection | `Shift`, `Ctrl`, or `⌘` + click |
| Select all layers | `Ctrl+A` |
| Move 1 / 10 pixels | Arrow keys / `Shift` + arrow keys |
| Move up / down one layer | `Ctrl+]` / `Ctrl+[` |
| Pan canvas | Space + drag, or middle-button drag |
| Zoom canvas | `Ctrl` + wheel, or bottom-right zoom controls |
| Fit canvas | `Ctrl+0` |
| Resize proportionally | `Shift` + drag the bottom-right corner |
| Temporarily disable 5-pixel snapping | `Alt` + drag |
| Deselect / cancel current drag | `Esc` |
| Open shortcut help | `?` or the top-right question mark |

On macOS, use `⌘` instead of `Ctrl`. The editor retains 100 editing steps for the current template. One drag is one undo step, and saving does not clear history. Switching templates or refreshing starts a new history.

Properties are grouped into position/size, text, appearance, layout, and visibility conditions. With nothing selected, edit canvas dimensions, background, and gradients. Color controls support `#AARRGGBB` and preserve alpha. A single layer aligns to the canvas; multiple layers align with each other. With a container selected, use the insertion target at the top: Inside selected container (`选中容器内`), When true (`条件成立时`), or When false (`条件不成立时`). Variable-bound positions or dimensions require fixed numeric values before dragging or keyboard nudging.

Switching templates first saves current edits. Entering YAML saves and synchronizes design changes; leaving YAML saves and parses the source. Save failures or invalid source retain the current view and edits. The top of the page shows draft save state, and closing or refreshing with unsaved changes prompts a warning. Saving a draft and publishing are separate: only Publish template (`发布模板`) creates a new published version.

The layout view edits structure and placement. Conditions and loops show structural examples. Use Preview (`预览`) for real data, images, and server font rendering; the result can be downloaded as PNG. The editor also provides asset uploads, complete layer JSON, provider resolution, release history, and rollback. Rollback changes the active published version while retaining the draft.

Java players on Spigot/Paper/Folia, BungeeCord, and Velocity receive Open editor (`打开编辑器`) and Copy link to chat input (`复制链接到输入框`) buttons. The first opens the browser through the client. The second places the full URL in chat input for copying with `Ctrl+A`, `Ctrl+C`, without sending a message. Nukkit-MOT players receive a form prefilled with the URL to copy into a browser. The console prints the full URL.

Keep the complete `/login?token=…` and allow cookies. After login you can refresh or revisit the used link in the same browser to access the current session. Different editor ports use separate cookies, so other localhost services do not interfere. After 24 hours without a successful renewal, losing cookies, or restarting the editor service, log in again. Missing or expired sessions display login instructions. If a session expires while editing, sign in in a new tab, then return to the original tab to save.

The bind address must resolve to loopback. For remote use, configure an HTTPS reverse proxy and preserve the original `Host`; do not expose the editor port directly to the Internet. Requests are also protected by session cookies, same-origin checks, a custom request header, CSP, upload limits, and template path restrictions.

## Resource limits

```yaml
custom-image-templates:
  limits:
    maximum-width: 2400
    maximum-height: 2400
    maximum-pixels: 8388608
    maximum-layers: 256
    maximum-loop-items: 200
    maximum-asset-bytes: 2097152
    maximum-template-asset-bytes: 16777216
    render-timeout-ms: 5000
  render:
    threads: 2
    maximum-queued: 16
  remote-images:
    enabled: true
  data:
    maximum-queries: 32
    timeout-ms: 3000
    cache-seconds: 10
```

The layer limit also counts nodes after loop expansion. Workers and waiting queues are bounded; full queues or timeouts fail quickly. Remote images require HTTPS and undergo checks for ports, redirects, private addresses, response types, bytes, and decoded pixel dimensions.

## Plugin API

Third-party plugins depend on the lightweight module with `provided` scope:

```xml
<dependency>
  <groupId>haaa</groupId>
  <artifactId>ShitBotApi</artifactId>
  <version>${shitbot.version}</version>
  <scope>provided</scope>
</dependency>
```

For Bukkit, obtain the API from the plugin's main class:

```java
Plugin plugin = Bukkit.getPluginManager().getPlugin("ShitBotSpigot");
if (!(plugin instanceof ShitBotApiProvider)) {
    return;
}
ShitBotApi api = ((ShitBotApiProvider) plugin).getShitBotApi();
if (api == null || !api.isCustomImageTemplatesEnabled()) {
    return;
}

Map<String, Object> context = new LinkedHashMap<String, Object>();
context.put("player", player.getName());
context.put("server", "survival");
api.renderImage("player-card", ImageRenderRequest.of(context))
        .thenAccept(result -> {
            byte[] png = result.getBytes();
            String fileName = result.getSuggestedFileName();
            long version = result.getTemplateVersion();
            // The calling plugin decides whether to send, cache, or save the image.
        });
```

`ImageRenderResult` contains PNG bytes, width, height, content type, suggested filename, template ID, and version. Calls are asynchronous; do not directly access main-thread/region-thread platform APIs from completion callbacks.

Register a custom provider:

```java
api.registerImageDataProvider(new ImageDataProvider() {
    public String getId() {
        return "my-plugin";
    }

    public CompletableFuture<Map<String, Object>> provide(ImageDataRequest request) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("value", "example");
        return CompletableFuture.completedFuture(data);
    }
});
```

After declaring `id: my-plugin` in the manifest, templates read `${data.my-plugin.value}`. Provider IDs must be unique. When unloading or reloading the third-party plugin, call `unregisterImageDataProvider` with the same provider instance.
