# Custom backgrounds and pixel coordinates

[简体中文](../pixel-templates.md) | **English**

Prepare your own PNG background and position text, avatars, and variables by pixel coordinates. This uses the existing advanced Java2D scene engine; no rendering code is needed.

## Prepare the background and draft

1. Enable `custom-image-templates.enabled` and the editor, reload, and sign in with `/shitbot editor`.
2. Create a template with ID `pixel-card`.
3. Upload your `background.png` to its assets. The example uses a 1000 × 600 canvas; adjust dimensions for your image.
4. Enter the manifest and scene below in YAML mode, or use the shared files in `docs/examples/pixel-card/` in the repository.

`manifest.yml`:

```yaml
schema-version: template-v1
id: pixel-card
name: Pixel-coordinate player card
suggested-file-name: player-card.png
providers: []
```

`scene.yml`:

```yaml
schema-version: scene-v1
canvas:
  width: 1000
  height: 600
  background: "#102030"
layers:
  - type: image
    source: assets/background.png
    x: 0
    y: 0
    width: 1000
    height: 600
    fit: stretch
  - type: text
    x: 120
    y: 160
    width: 760
    height: 60
    text: "Player: ${context.player}"
    font-size: 40
    font-style: bold
    color: "#FFFFFF"
  - type: text
    x: 120
    y: 245
    width: 760
    height: 40
    text: "Server: ${context.server}"
    font-size: 24
    color: "#B9E9DD"
```

The origin is the top-left corner: x increases rightward, y downward, in pixels. Layers paint in list order, so put the background first. Text x/y marks the top-left of its area; `width` limits that area. `fit: stretch` stretches the background. Use `contain` or `cover` to preserve its aspect ratio.

## Add PAPI variables

To read another plugin's data, replace `providers: []` with:

```yaml
providers:
  - id: papi
    player: "${context.player}"
    server: "${context.server}"
    placeholders:
      balance: "%vault_eco_balance_formatted%"
```

Add a text node with `text: "Balance: ${data.papi.balance}"` at the desired coordinates. The target Bukkit server needs PlaceholderAPI, Vault, an economy plugin, and the expansion supplying that placeholder. Proxy mode also needs the authenticated backend channel. Resolution errors appear in the editor preview.

## Publish and add a group command

Preview with a real player and backend name, then click Publish (`发布`). Production commands use only published versions. Add this to `commands.yml`:

```yaml
image-templates:
  commands:
    pixel-card:
      enabled: true
      aliases: ["my card"]
      template: "pixel-card"
      player-source: "bound"
      target-server: "survival"
      permission: ""
      cooldown-seconds: 10
      usage: "%at% Usage: my card"
      failed: "%at% Card generation failed: %result%"
```

Reload the command configuration, then send `my card` in QQ to use your most recently bound character. A whitelist entry without QQ cannot become a QQ user's personal card character. The local PNG does not require remote downloads. To change the background or coordinates later, save and publish a new draft; old versions can be restored through the editor.
