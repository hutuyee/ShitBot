# ShitBot 文档

## 安装和日常使用

- [安装与部署](installation.md)：选择平台 JAR、首次启动和部署方式。
- [配置说明](configuration.md)：`config.yml` 与 `commands.yml` 的主要配置。
- [命令与权限](commands.md)：管理命令、QQ 群指令、快捷命令和权限检查。
- [服务器启动提醒](startup-notices.md)：代理启动通知、等待指定子服、断线补发与重载。
- [PlaceholderAPI 变量](placeholders.md)：状态与绑定扩展，以及图片里的 PAPI 数据。
- [自己的底图与像素坐标文字](pixel-templates.md)：准备 PNG 底图并按坐标放置变量。
- [图片渲染与高级模板](image-templates.md)：内置/高级两种模式、按需组件、场景格式、编辑器和插件 API。
- [常见问题](troubleshooting.md)：连接、转发、绑定、数据库和代理命令排错。

## 群组服与数据

- [代理与后端子服](proxy-backend.md)：BungeeCord/Velocity 与 Spigot 后端的命令通道。
- [数据库与数据迁移](database.md)：SQLite、MySQL、平台迁移和 EasyBot 数据导入。
- [背包查询与材质配置](inventory.md)：离线背包快照、资源包、Mod 物品和自定义图标。

## 维护和开发

- [插件 API 与白名单管理](api.md)：异步绑定接口、管理命令和无 QQ 白名单。
- [平台兼容性](compatibility.md)：已验证环境、各平台差异和可选依赖。
- [升级与自动更新](updating.md)：手动升级、`/shitbot update` 和发布签名。
- [生产环境安全清单](security.md)：OneBot、数据库、命令通道、权限和日志安全。
- [构建与开发](development.md)：Maven 构建、模块结构和发布产物。

第一次安装时，依次阅读[安装与部署](installation.md)和[配置说明](configuration.md)即可。只有需要从 QQ 在子服执行命令时，才需要配置[代理与后端子服](proxy-backend.md)。
