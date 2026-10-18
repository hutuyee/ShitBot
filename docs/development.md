# 构建与开发

**简体中文** | [English](en/development.md)

本页面面向需要从源码构建或修改 ShitBot 的开发者。普通服主应从 [GitHub Releases](https://github.com/hutuyee/ShitBot/releases) 下载发布 JAR。

## 环境

- JDK 21；
- Maven 3.8 或更高版本；
- 可以访问 Maven Central、Spigot、BungeeCord、Velocity 和 Nukkit-MOT 依赖仓库。

整个聚合工程由 JDK 21 构建，各模块输出：

| 模块 | 字节码目标 |
| --- | --- |
| ShitBotApi | Java 8 |
| ShitBotCore | Java 8 |
| ShitBotRenderer | Java 8 |
| ShitBotSpigot | Java 8 |
| ShitBotBungee | Java 8 |
| ShitBotVelocity | Java 21 |
| ShitBotNukkit | Java 17 |

## 构建全部平台

在仓库根目录执行：

```powershell
mvn clean package
```

版本由 `.mvn/maven.config` 中的 `revision` 属性提供。需要临时指定版本时：

```powershell
mvn -Drevision=1.0.10-SNAPSHOT clean package
```

根 `pom.xml` 同时是聚合工程和七个模块的父 POM，统一管理依赖、插件版本、编码和默认 Java 8 目标；Velocity 和 Nukkit 分别覆盖为 Java 21 和 17。Compiler Plugin 使用 `release` 限制可用的 Java API，避免在 JDK 21 上编译出引用新 API 的 Java 8 字节码。

`flatten-maven-plugin` 在安装或部署前将发布 POM 中的 `${revision}` 展开为实际版本。安装 API 时使用 `mvn -pl ShitBotApi -am install`，同时安装父 POM；部署到 Maven 仓库时也应包含父 POM。生成的 `.flattened-pom.xml` 不纳入版本控制。

## 构建单个平台

使用 `-am` 同时构建依赖的 ShitBotApi 与 ShitBotCore：

```powershell
mvn -pl ShitBotSpigot -am package
mvn -pl ShitBotBungee -am package
mvn -pl ShitBotVelocity -am package
mvn -pl ShitBotNukkit -am package
```

最终插件 JAR 位于对应模块的 `target/`。不要发布 `original-*.jar`、sources、javadoc 或 test JAR。

## 测试

测试使用 JUnit 4.13.2 和 Maven Surefire 3.5.2，位于各模块的 `src/test/java/`。带测试的模块要求至少发现一个测试，避免测试目录或命名错误导致构建空跑。

只运行 API、Core 和 Renderer 的共享逻辑测试，无需服务端 API 仓库：

```powershell
mvn -pl ShitBotApi,ShitBotCore,ShitBotRenderer -am test
```

使用 JDK 21 运行全部测试并检查所有平台的打包：

```powershell
mvn clean verify
```

当前用例覆盖：

- API 输入副本、只读集合、渲染结果字节隔离和数据提供器 ID 校验；
- 控制台消息序列化、协议版本、非法枚举/长度/列表、HMAC 认证和挑战值绑定；
- 占位符 Unicode 往返、重复键、损坏数据和大小限制；
- 语言回退、语言文件路径、快捷命令别名冲突与超时配置；
- 背包格式 1/2/3 兼容、物品组件、槽位归一化和 GZIP 解压大小限制；
- 模板绑定、YAML 校验、发布快照、回滚、资源路径和资源大小限制；
- 无界面 PNG 渲染、条件/循环、渲染超时和展开图层数量限制；
- Folia 调度方法签名的兼容与拒绝路径。

这些测试使用内存数据和临时目录，不启动 Minecraft、OneBot、数据库或编辑器服务，也不访问远程图片。渲染测试只断言简单形状的内部像素，避免依赖系统字体。真实平台行为和外部服务联调仍需单独验证。

Surefire 的 XML 和文本报告位于各模块的 `target/surefire-reports/`。

## 模块结构

```text
ShitBot/
├─ ShitBotApi/        # 绑定、白名单、图片 API 与渲染 SPI，独立发布供开发者依赖
├─ ShitBotCore/       # OneBot、数据库、绑定、图片、更新和共享业务逻辑
├─ ShitBotRenderer/   # 单独发布并按需下载的高级 Java2D 场景渲染器与编辑器
├─ ShitBotSpigot/     # Bukkit、Paper、Folia 与后端命令监听
├─ ShitBotBungee/     # BungeeCord 平台入口
├─ ShitBotVelocity/   # Velocity 平台入口
├─ ShitBotNukkit/     # Nukkit-MOT 平台入口
├─ PictureBridge/     # 独立客户端模组的 Git 子模块
├─ docs/              # 项目文档
└─ pom.xml            # Maven 聚合工程
```

平台模块依赖 ShitBotCore，并将运行所需的共享依赖重定位到最终 JAR。平台 API 依赖以 provided 范围使用，不应打包进发布 JAR。`ShitBotRenderer` 不得成为平台模块依赖；它通过 `ShitBotApi` 的 SPI 在运行时隔离加载，否则会破坏默认插件的体积边界和按需下载语义。

## 配置资源

各平台默认配置位于：

```text
ShitBotSpigot/src/main/resources/
ShitBotBungee/src/main/resources/
ShitBotVelocity/src/main/resources/
ShitBotNukkit/src/main/resources/
```

修改配置结构时，应同步检查：

- 四个平台的 `config.yml`；
- 平台适用的 `commands.yml`；
- Core 中共享的 `lang/zh_CN.yml` 与 `lang/en_US.yml`；
- Core 中共享的 `templates/default.yml`；
- 配置加载器与默认值；
- 重载流程；
- 本文档中引用的配置示例。

Nukkit-MOT 不包含代理—后端传输配置，其他平台的 `commands.yml` 共享同一配置结构。

## PictureBridge 子模块

PictureBridge 是独立仓库和独立构建，不参与 ShitBot Maven 聚合工程。只修改服务端插件时，不需要构建 PictureBridge。

需要同时开发客户端模组时，克隆仓库后初始化子模块：

```powershell
git submodule update --init --recursive
```

PictureBridge 的构建说明见其独立仓库文档。

## CI 与 Release

GitHub Actions 在 push、pull request 和手动触发时：

1. 在 Linux JDK 8、17 和 Windows JDK 21 上运行 API、Core、Renderer 的 `clean verify`；
2. 在 Linux JDK 21 上执行全工程 `mvn clean verify`，包含 Spigot 调度适配器测试和四个平台打包；
3. 无论成功或失败，都汇总测试结果并上传已有的 Surefire 报告，保留 14 天；预期模块没有报告、没有执行任何测试或全部跳过时会失败；
4. 收集四个平台 JAR、一个 `ShitBotApi` JAR 和一个独立 `ShitBotRenderer` JAR，生成并校验 SHA-256 文件；
5. 检查项目类的 Java 字节码目标、入口和共享资源、Renderer SPI 与版本信息，以及平台 API、测试依赖和可选 Renderer 的打包隔离；
6. 上传发布构件 Artifact，保留 30 天。

矩阵任务不会因其他环境失败而提前取消；同一分支或 PR 的过期 CI 运行会被新运行取消。所有测试和构建统一汇总为 `CI checks`，可将它设为分支保护的必需检查。工作流使用只读仓库权限，只有 Release 上传任务获得写权限。

发布 GitHub Release 时，工作流还会：

1. 执行相同的测试矩阵和全平台构建，不跳过测试；只有 `CI checks` 成功才允许上传；
2. 使用仓库 Secret 中的 RSA 私钥签署四个平台 JAR、公开 API JAR 和可选渲染器 JAR；
3. 检查六个 JAR、六个 checksum 和六个签名，并使用当前 Release 源码中的内置公钥逐个验签；
4. 将 JAR、`.sha256` 和 `.sig` 上传到 Release。Release 工作流不会被新运行自动取消。

工作流在 `.github/workflows/release.yml` 中维护；测试报告汇总和发布构件检查位于 `.github/scripts/`，只依赖 Python 3 标准库。PictureBridge 保持独立 CI，不参与上述 Maven 测试与打包。

签名密钥管理见[升级与自动更新](updating.md)。

## 发布前文档

发布包含配置变更的版本时，应在 Release Notes 明确说明：

- 是否需要重新生成或补充 `config.yml`；
- 是否需要同步更新代理与后端；
- 数据库是否发生迁移；
- Java 或平台最低版本是否变化；
- 是否新增权限、端口或外部网络依赖。

## 双语文档维护

中文文档保留在 `README.md` 和 `docs/*.md`，英文文档位于 `README.en.md` 和 `docs/en/*.md`，手册文件名一一对应。更新功能说明时同步维护两种语言，并保留每页顶部的语言链接。

网站仓库通过 `npm run docs:sync -- ../ShitBot` 同时复制中英文手册到对应语言目录。通用模板示例仍放在 `docs/examples/`。
