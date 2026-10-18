package haaa.shitbotvelocity.config;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleSettings;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class VelocityConfigLoaderTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path dataDirectory;
    private VelocityConfigLoader loader;

    @Before
    public void createLoader() throws Exception {
        dataDirectory = temporary.newFolder("plugin").toPath();
        loader = new VelocityConfigLoader(dataDirectory, getClass().getClassLoader(), ignored -> { });
    }

    @Test
    public void firstLoadRestoresDefaultsAndKeepsOptionalServicesDisabled() throws Exception {
        Settings settings = loader.load();
        ConsoleSettings console = loader.loadConsoleSettings();

        assertEquals(2, settings.getConfigVersion());
        assertEquals(Settings.Image.Renderer.JAVA, settings.getImage().getRenderer());
        assertFalse(settings.getCustomImages().isEnabled());
        assertTrue(settings.getInventory().isScanModJars());
        assertTrue(loader.isBStatsEnabled());
        assertFalse(console.getBackendTransport().getListener().isEnabled());
        assertTrue(console.getBackendTransport().getEndpoints().isEmpty());
        for (String resource : Arrays.asList("config.yml", "commands.yml", "lang/zh_CN.yml",
                "lang/en_US.yml", "templates/default.yml")) {
            assertTrue("Missing restored resource " + resource, Files.isRegularFile(dataDirectory.resolve(resource)));
        }
        assertFalse(read("config.yml").contains("${"));
    }

    @Test
    public void incompleteUserConfigurationKeepsValuesCommentsAndLargeGroupIds() throws Exception {
        write("config.yml", "# managed locally", "config-version: 2", "language: en_US", "debug: true",
                "bstats:", "  enabled: false", "onebot:", "  allowed-group-ids: [9007199254740993]",
                "image:", "  server-name: '生存服 · Survival'", "custom-option: keep");
        Settings settings = loader.load();

        assertTrue(settings.isDebug());
        assertFalse(loader.isBStatsEnabled());
        assertEquals("en_US", settings.getTranslations().getLanguage());
        assertEquals("生存服 · Survival", settings.getImage().getServerName());
        assertEquals(Collections.singletonList(9007199254740993L), settings.getOneBot().getAllowedGroupIds());
        assertTrue(settings.getOneBot().isGroupAllowed(9007199254740993L));
        assertFalse(settings.getOneBot().isGroupAllowed(123L));
        assertEquals("shitbot.db", settings.getDatabase().getSqliteFile());
        assertTrue(read("config.yml").contains("# managed locally"));
        assertTrue(read("config.yml").contains("custom-option: keep"));
    }

    @Test
    public void repeatedLoadsReadNewSettingsAndMetricsToggleFromDisk() throws Exception {
        write("config.yml", "config-version: 2", "bstats:", "  enabled: false", "onebot:",
                "  allowed-group-ids: [123]");
        Settings before = loader.load();
        assertFalse(loader.isBStatsEnabled());

        write("config.yml", "config-version: 2", "bstats:", "  enabled: true", "onebot:",
                "  allowed-group-ids: [456]");
        Settings after = loader.load();

        assertEquals(Collections.singletonList(123L), before.getOneBot().getAllowedGroupIds());
        assertEquals(Collections.singletonList(456L), after.getOneBot().getAllowedGroupIds());
        assertFalse(after.getOneBot().isGroupAllowed(123L));
        assertTrue(loader.isBStatsEnabled());
    }

    @Test
    public void customLanguageUsesSelectedValuesFallbacksAndExplicitEmptyStrings() throws Exception {
        write("config.yml", "config-version: 2", "language: custom");
        write("lang/zh_CN.yml", "test:", "  selected: 后备值", "  fallback: 中文后备", "  empty: 不应显示");
        write("lang/custom.yml", "test:", "  selected: Custom value", "  empty: ''");
        Translations text = loader.load().getTranslations();

        assertEquals("custom", text.getLanguage());
        assertEquals("Custom value", text.get("test.selected"));
        assertEquals("中文后备", text.get("test.fallback"));
        assertEquals("", text.get("test.empty"));
        assertEquals("caller default", text.get("test.absent", "caller default"));
    }

    @Test
    public void missingCustomLanguageIsCreatedFromTheAdministratorsFallback() throws Exception {
        write("config.yml", "config-version: 2", "language: custom");
        write("lang/zh_CN.yml", "test:", "  fallback: 管理员后备");
        Translations text = loader.load().getTranslations();

        assertEquals("custom", text.getLanguage());
        assertEquals("管理员后备", text.get("test.fallback"));
        assertTrue(Files.isRegularFile(dataDirectory.resolve("lang/custom.yml")));
    }

    @Test
    public void missingCustomThemeCopiesFallbackWithoutChangingItsSelection() throws Exception {
        write("config.yml", "config-version: 2", "image:", "  template: ocean");
        write("templates/default.yml", "online:", "  layout:", "    outer-margin: 55");
        Settings settings = loader.load();

        assertEquals("ocean", settings.getImage().getTemplate().getName());
        assertEquals(55, settings.getImage().getTemplate().getInt("online.layout.outer-margin", 0, 100, 0));
        assertEquals("default", settings.getInventory().getTemplate().getName());
        assertTrue(Files.isRegularFile(dataDirectory.resolve("templates/ocean.yml")));
    }

    @Test
    public void legacyLanguageMigrationPreservesLaterAdministratorChanges() throws Exception {
        write("config.yml", "config-version: 1", "image:", "  title: '旧服 · 在线状态'", "onebot:",
                "  commands:", "    bind:", "      aliases: [' 登记 ', '/verify']");
        Settings migrated = loader.load();

        assertEquals("旧服 · 在线状态", migrated.getTranslations().get("image.title"));
        assertEquals(Arrays.asList("登记", "/verify"), migrated.getOneBot().getBindCommand().getAliases());
        assertTrue(read("lang/zh_CN.yml").contains("legacy-config-v1"));

        write("lang/zh_CN.yml", "image:", "  title: 管理员标题", "_migration:", "  legacy-config-v1: true");
        write("config.yml", "config-version: 1", "image:", "  title: 不应再次迁移");
        assertEquals("管理员标题", loader.load().getTranslations().get("image.title"));
    }

    @Test
    public void invalidYamlDuplicateKeysAndNonMappingRootsLeaveOriginalBytesIntact() throws Exception {
        for (String invalid : Arrays.asList("onebot: [unterminated\n",
                "onebot:\n  enabled: true\n  enabled: false\n", "- not\n- a mapping\n")) {
            byte[] original = invalid.getBytes(StandardCharsets.UTF_8);
            Files.write(dataDirectory.resolve("config.yml"), original);

            assertThrows(IOException.class, () -> loader.load());
            assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("config.yml")));
        }
    }

    @Test
    public void languageNamesCannotEscapeTheirDirectory() throws Exception {
        write("config.yml", "config-version: 2", "language: '../../escape'");

        assertThrows(IllegalArgumentException.class, () -> loader.load());
        assertFalse(Files.exists(dataDirectory.getParent().resolve("escape.yml")));
    }

    @Test
    public void themeNamesCannotEscapeTheirDirectory() throws Exception {
        write("config.yml", "config-version: 2", "image:", "  template: '../../escape'");

        assertThrows(IllegalArgumentException.class, () -> loader.load());
        assertFalse(Files.exists(dataDirectory.getParent().resolve("escape.yml")));
    }

    @Test
    public void consoleYamlKeepsEndpointTlsAndResolvesAliasConflicts() throws Exception {
        write("config.yml", "config-version: 2", "language: custom");
        write("lang/custom.yml", "console:", "  tps:", "    aliases: [status]");
        write("commands.yml", "backend-transport:", "  default-server: Survival", "  endpoints:",
                "    Survival:", "      host: backend.example.invalid", "      port: 25581",
                "      token: test-token-0123456789", "      tls:", "        enabled: true",
                "        trust-store: proxy.p12", "shortcuts:", "  inspect:",
                "    aliases: [STATUS, ' Inspect ']", "    command: ' list '", "    capture-seconds: 7s",
                "image-templates:", "  commands:", "    preview:", "      aliases: [inspect, ' Render ']",
                "      template: online-status");
        ConsoleSettings settings = loader.loadConsoleSettings();
        ConsoleSettings.BackendEndpoint endpoint = settings.getBackendTransport().getEndpoint(" SURVIVAL ");
        ConsoleSettings.Shortcut shortcut = settings.getShortcuts().stream()
                .filter(value -> "inspect".equals(value.getName())).findFirst().get();
        ConsoleSettings.ImageTemplateCommand preview = settings.getImageTemplateCommands().stream()
                .filter(value -> "preview".equals(value.getName())).findFirst().get();

        assertNotNull(endpoint);
        assertEquals("Survival", settings.getBackendTransport().getDefaultServer());
        assertEquals("backend.example.invalid", endpoint.getHost());
        assertEquals(25581, endpoint.getPort());
        assertEquals("test-token-0123456789", endpoint.getToken());
        assertTrue(endpoint.isTlsEnabled());
        assertEquals("proxy.p12", endpoint.getTlsTrustStore());
        assertEquals(Collections.singletonList("Inspect"), shortcut.getAliases());
        assertEquals("list", shortcut.getCommand());
        assertEquals(7, shortcut.getCaptureSeconds());
        assertFalse(shortcut.isAllowUnbound());
        assertEquals(Collections.singletonList("Render"), preview.getAliases());
    }

    private void write(String resource, String... lines) throws IOException {
        Path file = dataDirectory.resolve(resource);
        Files.createDirectories(file.getParent());
        Files.write(file, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String read(String resource) throws IOException {
        return new String(Files.readAllBytes(dataDirectory.resolve(resource)), StandardCharsets.UTF_8);
    }
}
