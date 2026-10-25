package haaa.shitbotnukkit.config;

import cn.nukkit.utils.Config;
import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.LegacyLanguageMigration;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSettingsFactory;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class NukkitConfigLoaderTest {
    @Test
    public void nativeYamlScalarsPreserveExplicitFalseZeroAndEmptyText() {
        Config config = yaml("onebot:", "  enabled: false", "  access-token: ''", "image:", "  cache-seconds: 0");
        NukkitConfigLoader.Source source = new NukkitConfigLoader.Source(config);

        assertFalse(source.getBoolean("onebot.enabled", true));
        assertEquals("", source.getString("onebot.access-token", "fallback"));
        assertEquals(0, source.getInt("image.cache-seconds", 8));
        assertTrue(source.getBoolean("missing.enabled", true));
        assertEquals(15, source.getInt("missing.timeout", 15));
        assertEquals("fallback", source.getString("missing.text", "fallback"));
    }

    @Test
    public void yamlLongValuesAndGroupIdsKeepTheirFullPrecision() {
        Config config = yaml("onebot:", "  allowed-group-ids: [123, 9007199254740993, 9223372036854775807]",
                "database:", "  pool:", "    maximum-lifetime-ms: 9007199254740993");
        NukkitConfigLoader.Source source = new NukkitConfigLoader.Source(config);

        assertEquals(9007199254740993L, source.getLong("database.pool.maximum-lifetime-ms", 1L));
        assertEquals(7L, source.getLong("missing", 7L));
        assertEquals(Arrays.asList(123L, 9007199254740993L, Long.MAX_VALUE), source.getLongList("onebot.allowed-group-ids"));
    }

    @Test
    public void listResultsCanBeChangedWithoutChangingNativeConfiguration() {
        Config config = yaml("aliases: ['绑定', '/verify']", "groups: [123, 9007199254740993]");
        NukkitConfigLoader.Source source = new NukkitConfigLoader.Source(config);
        List<String> aliases = source.getStringList("aliases");
        List<Long> groups = source.getLongList("groups");

        aliases.clear();
        groups.add(456L);
        assertEquals(Arrays.asList("绑定", "/verify"), source.getStringList("aliases"));
        assertEquals(Arrays.asList(123L, 9007199254740993L), source.getLongList("groups"));
        assertTrue(source.getStringList("missing").isEmpty());
        assertTrue(source.getLongList("missing").isEmpty());
    }

    @Test
    public void sectionKeysReturnOnlyDirectChildrenAndIndependentSets() {
        Config config = yaml("shortcuts:", "  inspect:", "    command: list", "  editor:", "    command: lp editor");
        NukkitConfigLoader.Source source = new NukkitConfigLoader.Source(config);
        Set<String> expected = new HashSet<String>(Arrays.asList("inspect", "editor"));

        Set<String> keys = source.getSectionKeys("shortcuts");
        assertEquals(expected, keys);
        keys.clear();
        assertEquals(expected, source.getSectionKeys("shortcuts"));
        assertTrue(source.getSectionKeys("missing").isEmpty());
    }

    @Test
    public void bedrockSettingsRetainChatPrefixGroupGateAndDisabledModScanning() {
        Config config = yaml("onebot:", "  allowed-group-ids: [9007199254740993]", "forwarding:",
                "  game-to-group:", "    enabled: true", "    prefix: '#qq '", "inventory:",
                "  icons:", "    scan-mod-jars: false");
        NukkitConfigLoader.Source source = new NukkitConfigLoader.Source(config);
        NukkitConfigLoader.Source empty = new NukkitConfigLoader.Source(new Config(Config.YAML));
        ImageTemplate template = new ImageTemplate("default", empty, empty);
        Settings settings = SettingsFactory.create(source, translations(new Config(Config.YAML)), template, template);

        assertTrue(settings.getForwarding().getGameToGroup().isEnabled());
        assertEquals("#qq ", settings.getForwarding().getGameToGroup().getPrefix());
        assertFalse(settings.getInventory().isScanModJars());
        assertTrue(settings.getOneBot().isGroupAllowed(9007199254740993L));
        assertFalse(settings.getOneBot().isGroupAllowed(123L));
    }

    @Test
    public void legacyMessagesAndAliasesMigrateOnceThroughNativeSections() {
        Config config = yaml("config-version: 1", "image:", "  title: '基岩服 · 在线状态'", "onebot:",
                "  commands:", "    bind:", "      aliases: ['登记', '/verify']");
        NukkitConfigLoader.Source legacy = new NukkitConfigLoader.Source(config);
        Config language = new Config(Config.YAML);

        assertTrue(LegacyLanguageMigration.isRequired(legacy, new NukkitConfigLoader.Source(language)));
        Map<String, Object> migrated = LegacyLanguageMigration.collect(legacy);
        assertEquals("基岩服 · 在线状态", migrated.get("image.title"));
        assertEquals(Arrays.asList("登记", "/verify"), migrated.get("commands.bind.aliases"));
        language.set(LegacyLanguageMigration.MARKER_PATH, true);
        assertFalse(LegacyLanguageMigration.isRequired(legacy, new NukkitConfigLoader.Source(language)));
    }

    @Test
    public void localConsoleShortcutsRequireBindingAndParseCaptureDuration() {
        Config config = yaml("shortcuts:", "  inspect:", "    aliases: ['STATUS', ' Inspect ']",
                "    command: ' list '", "    target: backend", "    capture-seconds: 7s");
        Config language = yaml("console:", "  tps:", "    aliases: [status]");
        ConsoleSettings settings = ConsoleSettingsFactory.create(
                new NukkitConfigLoader.Source(config), translations(language));

        assertEquals(1, settings.getShortcuts().size());
        ConsoleSettings.Shortcut shortcut = settings.getShortcuts().get(0);
        assertEquals(Collections.singletonList("Inspect"), shortcut.getAliases());
        assertEquals("list", shortcut.getCommand());
        assertEquals(ConsoleSettings.Target.BACKEND, shortcut.getTarget());
        assertEquals("", shortcut.getServer());
        assertEquals(7, shortcut.getCaptureSeconds());
        assertFalse(shortcut.isAllowUnbound());
        assertEquals("shitbot.admin", shortcut.getPermission());
        assertFalse(settings.getBackendTransport().getListener().isEnabled());
        assertTrue(settings.getBackendTransport().getEndpoints().isEmpty());
    }

    @Test
    public void generatedDefaultsParseInNukkitWithoutJavaPlatformSections() throws Exception {
        Config config = resource("config.yml");
        Config commands = resource("commands.yml");
        NukkitConfigLoader.Source main = new NukkitConfigLoader.Source(config);
        NukkitConfigLoader.Source console = new NukkitConfigLoader.Source(commands);

        assertEquals(2, main.getInt("config-version", 0));
        assertFalse(main.getBoolean("inventory.icons.scan-mod-jars", true));
        assertFalse(config.exists("bstats"));
        assertFalse(config.exists("deployment"));
        assertFalse(commands.exists("backend-transport"));
        assertFalse(console.getBoolean("image-templates.commands.inventory.enabled", true));
    }

    private static Translations translations(Config language) {
        return new Translations("zh_CN", new NukkitConfigLoader.Source(language),
                new NukkitConfigLoader.Source(new Config(Config.YAML)));
    }

    private static Config yaml(String... lines) {
        Config config = new Config(Config.YAML);
        byte[] contents = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        assertTrue("Nukkit should accept the YAML fixture", config.load(new ByteArrayInputStream(contents)));
        assertTrue(config.isCorrect());
        return config;
    }

    private static Config resource(String name) throws Exception {
        try (InputStream input = NukkitConfigLoaderTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull("Missing generated resource " + name, input);
            Config config = new Config(Config.YAML);
            assertTrue("Nukkit should accept " + name, config.load(input));
            assertTrue(config.isCorrect());
            return config;
        }
    }
}
