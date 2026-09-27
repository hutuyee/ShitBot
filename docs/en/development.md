# Building and development

[简体中文](../development.md) | **English**

This page is for developers building or modifying ShitBot. Server owners should download published JARs from [GitHub Releases](https://github.com/hutuyee/ShitBot/releases).

## Environment

- JDK 21.
- Maven 3.8 or newer.
- Access to Maven Central and the Spigot, BungeeCord, Velocity, and Nukkit-MOT dependency repositories.

The aggregate project builds with JDK 21 and produces:

| Module | Bytecode target |
| --- | --- |
| ShitBotApi | Java 8 |
| ShitBotCore | Java 8 |
| ShitBotRenderer | Java 8 |
| ShitBotSpigot | Java 8 |
| ShitBotBungee | Java 8 |
| ShitBotVelocity | Java 21 |
| ShitBotNukkit | Java 17 |

## Build all platforms

From the repository root:

```powershell
mvn clean package
```

The `revision` property in `.mvn/maven.config` supplies the version. To override it:

```powershell
mvn -Drevision=1.0.10-SNAPSHOT clean package
```

## Build one platform

Use `-am` to also build the required ShitBotApi and ShitBotCore modules:

```powershell
mvn -pl ShitBotSpigot -am package
mvn -pl ShitBotBungee -am package
mvn -pl ShitBotVelocity -am package
mvn -pl ShitBotNukkit -am package
```

Final plugin JARs are in each module's `target/`. Do not distribute `original-*.jar`, sources, javadoc, or test JARs as plugins.

## Module structure

```text
ShitBot/
├─ ShitBotApi/        # Binding, whitelist, image API and rendering SPI; published separately
├─ ShitBotCore/       # OneBot, database, binding, images, updates, and shared logic
├─ ShitBotRenderer/   # Separately published, optional Java2D scene renderer and editor
├─ ShitBotSpigot/     # Bukkit, Paper, Folia, and backend command listener
├─ ShitBotBungee/     # BungeeCord entry point
├─ ShitBotVelocity/   # Velocity entry point
├─ ShitBotNukkit/     # Nukkit-MOT entry point
├─ PictureBridge/    # Git submodule for the separate client mod
├─ docs/             # Chinese documentation and English translations in en/
└─ pom.xml           # Maven aggregate project
```

Platform modules depend on ShitBotCore and relocate the shared runtime dependencies into the final JAR. Platform APIs use `provided` scope and should not be bundled. `ShitBotRenderer` must not become a platform module dependency: it is loaded in isolation at runtime through the ShitBotApi SPI, preserving the small default plugin and optional download behavior.

## Configuration resources

Platform defaults are in:

```text
ShitBotSpigot/src/main/resources/
ShitBotBungee/src/main/resources/
ShitBotVelocity/src/main/resources/
ShitBotNukkit/src/main/resources/
```

When changing configuration structure, consider:

- All four `config.yml` files.
- The applicable `commands.yml` files.
- Shared Core `lang/zh_CN.yml` and `lang/en_US.yml`.
- Shared Core `templates/default.yml`.
- Loaders and default values.
- Reload behavior.
- Configuration examples in the documentation.

Nukkit-MOT has no proxy/backend transport configuration. Other platforms share the same `commands.yml` structure.

## PictureBridge submodule

PictureBridge has its own repository and build. It is not part of ShitBot's Maven aggregate project. You do not need to build it when only modifying the server plugin.

For client development, initialize the submodule after cloning:

```powershell
git submodule update --init --recursive
```

See PictureBridge's own documentation for build instructions.

## CI and releases

On pushes, pull requests, and manual runs, GitHub Actions:

1. Runs `mvn clean package` with JDK 21.
2. Collects four platform JARs, one ShitBotApi JAR, and one separate ShitBotRenderer JAR.
3. Generates SHA-256 files.
4. Uploads workflow artifacts.

For GitHub releases, the workflow also:

1. Signs all four platform JARs, the public API JAR, and the optional renderer JAR with the RSA private key in repository secrets.
2. Checks for six JARs, six checksums, and six signatures.
3. Uploads the JARs, `.sha256`, and `.sig` files.

See [Upgrading and automatic updates](updating.md) for signing key management.

## Release documentation

For releases with configuration changes, explain in the release notes:

- Whether `config.yml` needs regeneration or new entries.
- Whether proxies and backends must update together.
- Whether the database schema changes.
- Whether Java or platform minimums change.
- Whether new permissions, ports, or external network dependencies are introduced.

## Maintaining both documentation languages

Chinese documentation lives in `README.md` and `docs/*.md`. English documentation lives in `README.en.md` and `docs/en/*.md`, using the same manual filenames. Keep the language links at the top of each page and update both versions when behavior changes.

The website's `npm run docs:sync -- ../ShitBot` command copies both manuals into the corresponding Chinese and English sections. Shared template examples remain in `docs/examples/`.
