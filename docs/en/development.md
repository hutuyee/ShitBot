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

Tests use JUnit 4.13.2 and Maven Surefire 3.5.2 and live in each module's `src/test/java/`. All seven modules require at least one discovered test, so missing test directories or incorrect naming cannot silently produce an empty test run. CI summaries also reject missing reports or entirely skipped tests in expected modules.

Run the shared API, Core, and Renderer tests without platform API repositories:

```powershell
mvn -pl ShitBotApi,ShitBotCore,ShitBotRenderer -am test
```

With JDK 21, run all tests and package every platform:

```powershell
mvn clean verify
```

With JDK 21, run only the Bungee, Velocity, and Nukkit tests and their shared dependencies:

```powershell
mvn -pl ShitBotBungee,ShitBotVelocity,ShitBotNukkit -am test
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
- Bungee configuration adaptation for long integers, list copies, language migration, command aliases, binding requirements, and backend TLS settings.
- Nukkit native YAML types and group ID precision, configuration copies, language migration, command binding requirements, and platform defaults.
- Velocity configuration restoration, missing defaults, reloads, language and theme fallbacks, migration idempotence, invalid YAML rejection, and path restrictions.

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
├─ config-templates/  # Shared platform defaults and optional fragments
├─ PictureBridge/    # Git submodule for the separate client mod
├─ docs/             # Chinese documentation and English translations in en/
└─ pom.xml           # Maven aggregate project
```

Platform modules depend on ShitBotCore and relocate the shared runtime dependencies into the final JAR. Platform APIs use `provided` scope and should not be bundled. `ShitBotRenderer` must not become a platform module dependency: it is loaded in isolation at runtime through the ShitBotApi SPI, preserving the small default plugin and optional download behavior.

## Configuration resources

All four platforms share the defaults maintained in the root `config-templates/` directory:

```text
config-templates/config.yml              # Common main configuration
config-templates/commands.yml            # Common group command configuration
config-templates/bstats.yml              # Metrics for Java platforms
config-templates/spigot-deployment.yml   # Bukkit deployment role
config-templates/backend-transport.yml   # Java proxy/backend transport
```

Platform POMs select fragments through `config.defaults.fragments` and `config.commands.fragments`. During `generate-resources`, AntRun concatenates them in order into each module's `target/generated-resources/defaults/`; Maven Resources then filters platform properties and copies the files to the JAR root. Builds still require only the existing JDK and Maven. Do not edit generated files or add platform copies of the defaults.

Spigot includes metrics, deployment, and transport fragments. BungeeCord and Velocity include metrics and transport. Nukkit-MOT uses only the common configurations and sets `config.scan-mod-jars=false` to preserve its disabled Mod JAR scanning default; the other three platforms enable scanning by default. Plugin descriptors remain in each platform's `src/main/resources/`.

When changing configuration structure, consider:

- Shared `config.yml`, `commands.yml`, and the relevant fragments.
- Fragment selection and filtering properties in platform POMs.
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

1. Reads the single `revision` from `.mvn/maven.config` and passes it explicitly to subsequent Maven jobs.
2. Runs `clean test` for API, Core, and Renderer on Linux with JDK 8 and 17, and Windows with JDK 21, checking compatibility without repeating packaging and shading.
3. Runs the full `mvn clean verify` build on Linux with JDK 21, including unit tests and packaging for all four platforms.
4. Summarizes results and uploads available Surefire reports even when the build fails, retaining them for 14 days. Missing reports, zero executed tests, or entirely skipped tests in an expected module fail the job.
5. Collects four platform JARs, one ShitBotApi JAR, and one separate ShitBotRenderer JAR; generates and verifies their SHA-256 files.
6. Checks that all six filenames, embedded Maven coordinates and POMs, plugin descriptors, and Renderer version metadata match `revision`. Published POMs must not retain `${revision}`.
7. Checks project bytecode targets, entry points and shared resources, Renderer SPI, and packaging isolation for platform APIs, test dependencies, and the optional Renderer. Checks that platform defaults exist without unresolved placeholders and contain the correct deployment, metrics, transport, and Mod JAR scanning defaults.
8. Uploads release build artifacts, retaining them for 30 days.

All seven modules have JUnit cases, and the full platform job summarizes and requires executed tests from every module. Bungee cases exercise adaptation of native `Configuration` objects; Nukkit cases parse YAML with native `Config`; Velocity cases run configuration loading against temporary files. These cases do not start a proxy or server, so real platform behavior still needs separate integration checks. Compilation, packaging, descriptor, and default configuration checks remain separate artifact checks.

Matrix jobs continue if another environment fails. New runs cancel outdated CI runs for the same branch or PR. The stable `CI checks` job aggregates version, test, and build results and can be required by branch protection. Workflow permissions default to read-only; only the Release upload job receives write access.

For GitHub releases, the workflow also:

1. Before building, requires the release tag, with an optional `v` or `V` prefix, to match the source revision exactly. For example, `v1.2.0` requires `-Drevision=1.2.0`; mismatches fail immediately.
2. Runs the same test matrix and full platform build without skipping tests; uploads require a successful `CI checks` result.
3. Rechecks downloaded artifact contents and versions, then signs all four platform JARs, the public API JAR, and the optional renderer JAR with the RSA private key in repository secrets.
4. Checks for six JARs, six checksums, and six signatures, verifying each signature against the built-in public key in the release source.
5. Uploads the JARs, `.sha256`, and `.sig` files. New runs do not automatically cancel release workflows.

The workflow lives in `.github/workflows/release.yml`. Version resolution, report summarization, and release artifact checks live in `.github/scripts/` and require only the Python 3 standard library. Run artifact checks with `python .github/scripts/check-artifacts.py release --version 1.2.0`, using the version of that build. PictureBridge keeps its independent CI and is not part of these Maven tests or packages.

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
