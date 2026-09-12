# 自己的底图与像素坐标文字

管理员可以准备自己的 PNG 底图，按像素填写文字、头像和变量位置。这种方式使用现有高级 Java2D 场景引擎；不需要编写渲染代码。

## 准备底图与草稿

1. 开启 `custom-image-templates.enabled` 和编辑器，重载后用 `/shitbot editor` 登录。
2. 新建 ID 为 `pixel-card` 的模板。
3. 上传自己的图片 `background.png` 到模板资源区。下面的示例使用 1000 × 600 的画布；按你自己的图片尺寸修改宽高。
4. 在 YAML 模式填写下面的 manifest 与 scene，或使用仓库的 `docs/examples/pixel-card/` 示例文件。

`manifest.yml`：

```yaml
schema-version: template-v1
id: pixel-card
name: 像素坐标名片
suggested-file-name: player-card.png
providers: []
```

`scene.yml`：

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
    text: "玩家：${context.player}"
    font-size: 40
    font-style: bold
    color: "#FFFFFF"
  - type: text
    x: 120
    y: 245
    width: 760
    height: 40
    text: "服务器：${context.server}"
    font-size: 24
    color: "#B9E9DD"
```

坐标原点在画布左上角，x 向右、y 向下，单位为像素。图层按列表顺序覆盖绘制，因此底图放在第一层。文本的 x/y 表示文本区域左上角；`width` 限制文字区域。`fit: stretch` 会拉伸底图，若需要保持比例可使用 `contain` 或 `cover`。

## 加入 PAPI 变量

需要读取其他插件数据时，把 `providers: []` 替换为：

```yaml
providers:
  - id: papi
    player: "${context.player}"
    server: "${context.server}"
    placeholders:
      balance: "%vault_eco_balance_formatted%"
```

再在指定像素位置添加 `text: "余额：${data.papi.balance}"` 的文本节点。目标 Bukkit 服务端需要安装 PlaceholderAPI、Vault、经济插件和提供此变量的扩展；代理模式还需配置认证后端通道。解析错误会显示在编辑器预览里。

## 发布与群命令

在编辑器中用实际玩家名和子服名预览，完成后点“发布”。只有已发布版本会被生产命令读取。随后在 `commands.yml` 增加：

```yaml
image-templates:
  commands:
    pixel-card:
      enabled: true
      aliases: ["我的名片"]
      template: "pixel-card"
      player-source: "bound"
      target-server: "survival"
      permission: ""
      cooldown-seconds: 10
      usage: "%at% 用法：我的名片"
      failed: "%at% 名片生成失败：%result%"
```

重载命令配置后，QQ 群发送“我的名片”即可使用自己的最新绑定角色。无 QQ 白名单不能成为任何 QQ 的个人名片角色。这里的本地 PNG 不需要开启远程图片下载。后续更换底图或坐标，保存草稿并重新发布即可；旧版本可在编辑器里回滚。
