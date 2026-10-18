package haaa.shitbotbungee.config;

import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.LegacyLanguageMigration;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSettingsFactory;
import net.md_5.bungee.config.Configuration;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class BungeeConfigLoaderTest {
    @Test
    public void scalarDefaultsDoNotReplaceExplicitFalseZeroOrEmptyText() {
        Configuration config = new Configuration();
        config.set("onebot.enabled", false);
        config.set("image.cache-seconds", 0);
        config.set("onebot.access-token", "");
        BungeeConfigLoader.Source source = new BungeeConfigLoader.Source(config);

        assertFalse(source.getBoolean("onebot.enabled", true));
        assertEquals(0, source.getInt("image.cache-seconds", 8));
        assertEquals("", source.getString("onebot.access-token", "fallback"));
        assertTrue(source.getBoolean("missing.enabled", true));
        assertEquals(15, source.getInt("missing.timeout", 15));
        assertEquals("fallback", source.getString("missing.text", "fallback"));
    }

    @Test
    public void longValuesPreservePrecisionAndAcceptQuotedNumbers() {
        Configuration config = new Configuration();
        config.set("limits.numeric", 9007199254740993L);
        config.set("limits.quoted", " 9223372036854775807 ");
        config.set("limits.invalid", "not-a-number");
        config.set("limits.overflow", "9223372036854775808");
        BungeeConfigLoader.Source source = new BungeeConfigLoader.Source(config);

        assertEquals(9007199254740993L, source.getLong("limits.numeric", 7L));
        assertEquals(Long.MAX_VALUE, source.getLong("limits.quoted", 7L));
        assertEquals(7L, source.getLong("limits.invalid", 7L));
        assertEquals(7L, source.getLong("limits.overflow", 7L));
        assertEquals(7L, source.getLong("limits.missing", 7L));
    }

    @Test
    public void aliasListsRemoveEmptyEntriesAndReturnIndependentCopies() {
        Configuration config = new Configuration();
        config.set("aliases", Arrays.asList(null, "", "  ", " 查询 ", 42));
        config.set("scalar", "not-a-list");
        BungeeConfigLoader.Source source = new BungeeConfigLoader.Source(config);

        List<String> aliases = source.getStringList("aliases");
        assertEquals(Arrays.asList("查询", "42"), aliases);
        aliases.clear();
        assertEquals(Arrays.asList("查询", "42"), source.getStringList("aliases"));
        assertTrue(source.getStringList("scalar").isEmpty());
        assertTrue(source.getStringList("missing").isEmpty());
    }

    @Test
    public void malformedGroupIdsAreIgnoredWithoutOpeningOtherGroups() {
        Configuration config = new Configuration();
        config.set("onebot.allowed-group-ids", Arrays.asList(123, " 9007199254740993 ",
                null, "invalid", "9223372036854775808"));
        BungeeConfigLoader.Source source = new BungeeConfigLoader.Source(config);
        BungeeConfigLoader.Source empty = new BungeeConfigLoader.Source(new Configuration());
        ImageTemplate template = new ImageTemplate("default", empty, empty);
        Settings settings = SettingsFactory.create(source, translations(new Configuration()), template, template);

        assertEquals(Arrays.asList(123L, 9007199254740993L), settings.getOneBot().getAllowedGroupIds());
        assertTrue(settings.getOneBot().isGroupAllowed(9007199254740993L));
        assertFalse(settings.getOneBot().isGroupAllowed(456L));
        assertFalse(settings.getOneBot().isAllowAllGroups());
        source.getLongList("onebot.allowed-group-ids").clear();
        assertEquals(Arrays.asList(123L, 9007199254740993L), source.getLongList("onebot.allowed-group-ids"));
    }

    @Test
    public void sectionKeysExposeDirectChildrenWithoutMutableBackingState() {
        Configuration config = new Configuration();
        config.set("shortcuts.inspect.command", "list");
        config.set("shortcuts.editor.command", "lp editor");
        BungeeConfigLoader.Source source = new BungeeConfigLoader.Source(config);
        Set<String> expected = new HashSet<String>(Arrays.asList("inspect", "editor"));

        Set<String> keys = source.getSectionKeys("shortcuts");
        assertEquals(expected, keys);
        keys.clear();
        assertEquals(expected, source.getSectionKeys("shortcuts"));
        assertTrue(source.getSectionKeys("missing").isEmpty());
    }

    @Test
    public void legacyLanguageMigrationUsesBungeeNestedPathsAndMarker() {
        Configuration config = new Configuration();
        config.set("config-version", 1);
        config.set("image.title", "旧服 · 在线状态");
        config.set("onebot.commands.bind.aliases", Arrays.asList(" 登记 ", "/verify"));
        BungeeConfigLoader.Source legacy = new BungeeConfigLoader.Source(config);
        Configuration language = new Configuration();

        assertTrue(LegacyLanguageMigration.isRequired(legacy, new BungeeConfigLoader.Source(language)));
        Map<String, Object> migrated = LegacyLanguageMigration.collect(legacy);
        assertEquals("旧服 · 在线状态", migrated.get("image.title"));
        assertEquals(Arrays.asList("登记", "/verify"), migrated.get("commands.bind.aliases"));
        language.set(LegacyLanguageMigration.MARKER_PATH, true);
        assertFalse(LegacyLanguageMigration.isRequired(legacy, new BungeeConfigLoader.Source(language)));
    }

    @Test
    public void consoleAliasesAreUniqueAndPrivilegedShortcutsRequireBinding() {
        Configuration config = new Configuration();
        config.set("shortcuts.inspect.aliases", Arrays.asList("STATUS", " Inspect "));
        config.set("shortcuts.inspect.command", " list ");
        config.set("shortcuts.inspect.capture-seconds", "7s");
        config.set("image-templates.commands.preview.aliases", Arrays.asList("inspect", " Render "));
        config.set("image-templates.commands.preview.template", "online-status");
        Configuration language = new Configuration();
        language.set("console.tps.aliases", Collections.singletonList("status"));
        ConsoleSettings settings = ConsoleSettingsFactory.create(
                new BungeeConfigLoader.Source(config), translations(language));

        assertEquals(Collections.singletonList("Inspect"), settings.getShortcuts().get(0).getAliases());
        assertEquals("list", settings.getShortcuts().get(0).getCommand());
        assertEquals(7, settings.getShortcuts().get(0).getCaptureSeconds());
        assertFalse(settings.getShortcuts().get(0).isAllowUnbound());
        assertEquals("shitbot.admin", settings.getShortcuts().get(0).getPermission());
        assertEquals(Collections.singletonList("Render"), settings.getImageTemplateCommands().get(0).getAliases());
    }

    @Test
    public void backendEndpointsKeepTlsCredentialsAndCaseInsensitiveLookup() {
        Configuration config = new Configuration();
        String endpoint = "backend-transport.endpoints.Survival.";
        config.set(endpoint + "host", "backend.example.invalid");
        config.set(endpoint + "port", 25581);
        config.set(endpoint + "token", "test-token-0123456789");
        config.set(endpoint + "tls.enabled", true);
        config.set(endpoint + "tls.trust-store", "proxy.p12");
        config.set(endpoint + "tls.trust-store-password", "test-password");
        ConsoleSettings settings = ConsoleSettingsFactory.create(
                new BungeeConfigLoader.Source(config), translations(new Configuration()));
        ConsoleSettings.BackendEndpoint loaded = settings.getBackendTransport().getEndpoint(" SURVIVAL ");

        assertNotNull(loaded);
        assertSame(loaded, settings.getBackendTransport().getOnlyEndpoint());
        assertEquals("backend.example.invalid", loaded.getHost());
        assertEquals(25581, loaded.getPort());
        assertEquals("test-token-0123456789", loaded.getToken());
        assertTrue(loaded.isTlsEnabled());
        assertEquals("proxy.p12", loaded.getTlsTrustStore());
        assertEquals("test-password", loaded.getTlsTrustStorePassword());
        assertFalse(settings.getBackendTransport().getListener().isEnabled());
    }

    private static Translations translations(Configuration language) {
        return new Translations("zh_CN", new BungeeConfigLoader.Source(language),
                new BungeeConfigLoader.Source(new Configuration()));
    }
}
