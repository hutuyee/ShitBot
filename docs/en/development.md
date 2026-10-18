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

The root `pom.xml` is both the aggregator and the parent of all seven modules. It manages dependency and plugin versions, encoding, and the default Java 8 target; Velocity and Nukkit override the target to Java 21 and 17. The Compiler Plugin uses `release` to restrict available Java APIs, preventing Java 8 bytecode compiled on JDK 21 from referencing newer APIs.

Before installation or deployment, `flatten-maven-plugin` resolves `${revision}` to the actual version in published POMs. Install the API with `mvn -pl ShitBotApi -am install` so the parent POM is installed as well; include the parent when deploying to a Maven repository too. Generated `.flattened-pom.xml` files are ignored by Git.

## Build one platform

Use `-am` to also build the required ShitBotApi and ShitBotCore modules:

```powershell
mvn -pl ShitBotSpigot -am package
mvn -pl ShitBotBungee -am package
mvn -pl ShitBotVelocity -am package
mvn -pl ShitBotNukkit -am package
```

Final plugin JARs are in each module's `target/`. Do not distribute `original-*.jar`, sources, javadoc, or test JARs as plugins.

## Tests

Tests use JUnit 4.13.2 and Maven Surefire 3.5.2 and live in each module's `src/test/java/`. Modules with tests require at least one discovered test, so missing test directories or incorrect naming cannot silently produce an empty test run.

Run the shared API, Core, and Renderer tests without platform API repositories:

```powershell
mvn -pl ShitBotApi,ShitBotCore,ShitBotRenderer -am test
```

With JDK 21, run all tests and package every platform:

```powershell
mvn clean verify
```

Current cases cover:

- API input copies, read-only collections, rendered byte isolation, and provider ID validation.
- Console serialization, protocol versions, invalid enums/lengths/lists, HMAC authentication, and challenge binding.
- Placeholder Unicode round trips, duplicate keys, malformed data, and size limits.
- Language fallbacks and paths, shortcut alias conflicts, and timeout configuration.
- Inventory formats 1/2/3, item components, slot normalization, and GZIP expansion limits.
- Template bindings, YAML validation, published snapshots, rollback, asset paths, and asset size limits.
- Headless PNG rendering, conditions/loops, render deadlines, and expanded layer limits.
- Compatible and incompatible Folia scheduler method signatures.

These tests use in-memory data and temporary directories. They do not start Minecraft, OneBot, a database, or the editor server, and do not fetch remote images. Rendering assertions use interior pixels of simple shapes to avoid system font dependencies. Real platform behavior and external service integration still require separate validation.

Surefire XML and text reports are written to each module's `target/surefire-reports/`.

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

1. Runs `clean verify` for API, Core, and Renderer on Linux with JDK 8 and 17, and Windows with JDK 21.
2. Runs the full `mvn clean verify` build on Linux with JDK 21, including Spigot scheduler tests and all four platform packages.
3. Summarizes results and uploads available Surefire reports even when the build fails, retaining them for 14 days. Missing reports, zero executed tests, or entirely skipped tests in an expected module fail the job.
4. Collects four platform JARs, one ShitBotApi JAR, and one separate ShitBotRenderer JAR; generates and verifies their SHA-256 files.
5. Checks project bytecode targets, entry points and shared resources, Renderer SPI and version metadata, and packaging isolation for platform APIs, test dependencies, and the optional Renderer.
6. Uploads release build artifacts, retaining them for 30 days.

Matrix jobs continue if another environment fails. New runs cancel outdated CI runs for the same branch or PR. The stable `CI checks` job aggregates every test and build result and can be required by branch protection. Workflow permissions default to read-only; only the Release upload job receives write access.

For GitHub releases, the workflow also:

1. Runs the same test matrix and full platform build without skipping tests; uploads require a successful `CI checks` result.
2. Signs all four platform JARs, the public API JAR, and the optional renderer JAR with the RSA private key in repository secrets.
3. Checks for six JARs, six checksums, and six signatures, verifying each signature against the built-in public key in the release source.
4. Uploads the JARs, `.sha256`, and `.sig` files. New runs do not automatically cancel release workflows.

The workflow lives in `.github/workflows/release.yml`. Report summarization and release artifact checks live in `.github/scripts/` and require only the Python 3 standard library. PictureBridge keeps its independent CI and is not part of these Maven tests or packages.

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
