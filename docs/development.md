# 构建与开发

本页面面向需要从源码构建或修改 ShitBot 的开发者。普通服主应从 [GitHub Releases](https://github.com/hutuyee/ShitBot/releases) 下载发布 JAR。

## 环境

- JDK 21；
- Maven 3.8 或更高版本；
- 可以访问 Maven Central、Spigot、BungeeCord、Velocity 和 Nukkit-MOT 依赖仓库。

整个聚合工程由 JDK 21 构建，各模块输出：

| 模块 | 字节码目标 |
| --- | --- |
| ShitBotCore | Java 8 |
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

## 构建单个平台

使用 `-am` 同时构建依赖的 ShitBotCore：

```powershell
mvn -pl ShitBotSpigot -am package
mvn -pl ShitBotBungee -am package
mvn -pl ShitBotVelocity -am package
mvn -pl ShitBotNukkit -am package
```

最终插件 JAR 位于对应模块的 `target/`。不要发布 `original-*.jar`、sources、javadoc 或 test JAR。

## 模块结构

```text
ShitBot/
├─ ShitBotCore/       # OneBot、数据库、绑定、图片、更新和共享业务逻辑
├─ ShitBotSpigot/     # Bukkit、Paper、Folia 与后端命令监听
├─ ShitBotBungee/     # BungeeCord 平台入口
├─ ShitBotVelocity/   # Velocity 平台入口
├─ ShitBotNukkit/     # Nukkit-MOT 平台入口
├─ PictureBridge/     # 独立客户端模组的 Git 子模块
├─ docs/              # 项目文档
└─ pom.xml            # Maven 聚合工程
```

平台模块依赖 ShitBotCore，并将运行所需的共享依赖重定位到最终 JAR。平台 API 依赖以 provided 范围使用，不应打包进发布 JAR。

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

1. 使用 JDK 21 执行 `mvn clean package`；
2. 收集 Spigot、BungeeCord、Velocity 和 Nukkit-MOT 四个平台 JAR；
3. 为每个 JAR 生成 SHA-256 文件；
4. 上传工作流 Artifact。

发布 GitHub Release 时，工作流还会：

1. 使用仓库 Secret 中的 RSA 私钥签署四个平台 JAR；
2. 检查四个 JAR、四个 checksum 和四个签名；
3. 将 JAR、`.sha256` 和 `.sig` 上传到 Release。

签名密钥管理见[升级与自动更新](updating.md)。

## 发布前文档

发布包含配置变更的版本时，应在 Release Notes 明确说明：

- 是否需要重新生成或补充 `config.yml`；
- 是否需要同步更新代理与后端；
- 数据库是否发生迁移；
- Java 或平台最低版本是否变化；
- 是否新增权限、端口或外部网络依赖。
