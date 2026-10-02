package haaa.shitbotspigot.config;

import haaa.shitbot.core.config.ConfigResources;
import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.LegacyLanguageMigration;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSettingsFactory;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpigotConfigLoader {
    private final JavaPlugin plugin;
    private final ConfigResources resources;

    public SpigotConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
        this.resources = new ConfigResources(plugin.getDataFolder().toPath(), plugin::getResource);
    }

    public Settings load() {
        reloadMainConfig();
        Source source = new Source(plugin.getConfig());
        return SettingsFactory.create(
                source,
                loadTranslations(source),
                loadImageTemplate(source.getString("image.template", ImageTemplate.DEFAULT_TEMPLATE)),
                loadImageTemplate(source.getString("inventory.template", ImageTemplate.DEFAULT_TEMPLATE)));
    }

    public boolean isBStatsEnabled() {
        reloadMainConfig();
        return plugin.getConfig().getBoolean("bstats.enabled", true);
    }

    public ConsoleSettings loadConsoleSettings() {
        reloadMainConfig();
        File file = completeFile("commands.yml");
        Source config = new Source(plugin.getConfig());
        return ConsoleSettingsFactory.create(
                new Source(loadYaml(file)),
                loadTranslations(config));
    }

    public boolean isBackendMode() {
        return "backend".equalsIgnoreCase(plugin.getConfig().getString("deployment.role", "standalone"));
    }

    private void reloadMainConfig() {
        try {
            File file = resources.ensure("config.yml").toFile();
            // Read the actual file before defaults can introduce config-version: 2.
            Source legacy = new Source(loadYaml(file));
            migrateLegacyLanguage(legacy, completeFile(Translations.resourcePath(Translations.DEFAULT_LANGUAGE)));
            resources.complete("config.yml");
            plugin.reloadConfig();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to complete config.yml", exception);
        }
    }

    private File completeFile(String resource) {
        try {
            return resources.complete(resource).toFile();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to complete " + resource, exception);
        }
    }

    private YamlConfiguration loadYaml(File file) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
            return configuration;
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Unable to load " + file, exception);
        }
    }

    private Translations loadTranslations(Source config) {
        ensureLanguageFile(Translations.DEFAULT_LANGUAGE);
        ensureLanguageFile("en_US");
        File fallbackFile = languageFile(Translations.DEFAULT_LANGUAGE);
        migrateLegacyLanguage(config, fallbackFile);
        String language = Translations.normalizeLanguage(config.getString("language", Translations.DEFAULT_LANGUAGE));
        File selectedFile = languageFile(language);
        ensureLanguageFile(language);
        Source selected = new Source(loadYaml(selectedFile));
        Source fallback = new Source(loadYaml(fallbackFile));
        return new Translations(language, selected, fallback);
    }

    private void migrateLegacyLanguage(Source config, File fallbackFile) {
        YamlConfiguration language = loadYaml(fallbackFile);
        if (!LegacyLanguageMigration.isRequired(config, new Source(language))) {
            return;
        }
        for (Map.Entry<String, Object> entry : LegacyLanguageMigration.collect(config).entrySet()) {
            language.set(entry.getKey(), entry.getValue());
        }
        language.set(LegacyLanguageMigration.MARKER_PATH, true);
        try {
            language.save(fallbackFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to migrate legacy messages to " + fallbackFile, exception);
        }
    }

    private ImageTemplate loadImageTemplate(String configuredName) {
        ensureImageTemplateFile(ImageTemplate.DEFAULT_TEMPLATE);
        String name = ImageTemplate.normalizeName(configuredName);
        File selectedFile = imageTemplateFile(name);
        ensureImageTemplateFile(name);
        return new ImageTemplate(
                name,
                new Source(loadYaml(selectedFile)),
                new Source(loadYaml(
                        imageTemplateFile(ImageTemplate.DEFAULT_TEMPLATE))));
    }

    private void ensureImageTemplateFile(String name) {
        completeFile(ImageTemplate.resourcePath(name));
    }

    private File imageTemplateFile(String name) {
        return new File(plugin.getDataFolder(), ImageTemplate.resourcePath(name));
    }

    private void ensureLanguageFile(String language) {
        completeFile(Translations.resourcePath(language));
    }

    private File languageFile(String language) {
        return new File(plugin.getDataFolder(), Translations.resourcePath(language));
    }

    private static final class Source implements ConfigSource {
        private final FileConfiguration configuration;

        private Source(FileConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public String getString(String path, String fallback) {
            String value = configuration.getString(path, fallback);
            return value == null ? fallback : value;
        }

        @Override
        public boolean getBoolean(String path, boolean fallback) {
            return configuration.getBoolean(path, fallback);
        }

        @Override
        public int getInt(String path, int fallback) {
            return configuration.getInt(path, fallback);
        }

        @Override
        public long getLong(String path, long fallback) {
            return configuration.getLong(path, fallback);
        }

        @Override
        public List<String> getStringList(String path) {
            List<String> values = configuration.getStringList(path);
            return values == null ? Collections.<String>emptyList() : values;
        }

        @Override
        public List<Long> getLongList(String path) {
            List<?> values = configuration.getList(path);
            if (values == null) {
                return Collections.emptyList();
            }
            List<Long> result = new ArrayList<Long>();
            for (Object value : values) {
                if (value == null) {
                    continue;
                }
                try {
                    result.add(Long.valueOf(String.valueOf(value).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            return result;
        }

        @Override
        public Set<String> getSectionKeys(String path) {
            ConfigurationSection section = configuration.getConfigurationSection(path);
            return section == null
                    ? Collections.<String>emptySet()
                    : new LinkedHashSet<String>(section.getKeys(false));
        }
    }
}
