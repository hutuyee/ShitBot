package haaa.shitbotspigot.config;

import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSettingsFactory;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SpigotConfigLoader {
    private final JavaPlugin plugin;

    public SpigotConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Settings load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        Source source = new Source(plugin.getConfig());
        return SettingsFactory.create(
                source,
                loadTranslations(source),
                loadImageTemplate(source.getString("image.template", ImageTemplate.DEFAULT_TEMPLATE)),
                loadImageTemplate(source.getString("inventory.template", ImageTemplate.DEFAULT_TEMPLATE)));
    }

    public boolean isBStatsEnabled() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        return plugin.getConfig().getBoolean("bstats.enabled", true);
    }

    public ConsoleSettings loadConsoleSettings() {
        File file = new File(plugin.getDataFolder(), "commands.yml");
        if (!file.isFile()) {
            plugin.saveResource("commands.yml", false);
        }
        Source config = new Source(plugin.getConfig());
        return ConsoleSettingsFactory.create(
                new Source(YamlConfiguration.loadConfiguration(file)),
                loadTranslations(config));
    }

    public boolean isBackendMode() {
        return "backend".equalsIgnoreCase(plugin.getConfig().getString("deployment.role", "standalone"));
    }

    private Translations loadTranslations(Source config) {
        ensureLanguageFile(Translations.DEFAULT_LANGUAGE);
        ensureLanguageFile("en_US");
        String language = Translations.normalizeLanguage(config.getString("language", Translations.DEFAULT_LANGUAGE));
        File selectedFile = languageFile(language);
        if (!selectedFile.isFile()) {
            ensureLanguageFile(language);
        }
        Source selected = new Source(YamlConfiguration.loadConfiguration(selectedFile));
        Source fallback = new Source(YamlConfiguration.loadConfiguration(
                languageFile(Translations.DEFAULT_LANGUAGE)));
        return new Translations(language, selected, fallback);
    }

    private ImageTemplate loadImageTemplate(String configuredName) {
        ensureImageTemplateFile(ImageTemplate.DEFAULT_TEMPLATE);
        String name = ImageTemplate.normalizeName(configuredName);
        File selectedFile = imageTemplateFile(name);
        if (!selectedFile.isFile()) {
            ensureImageTemplateFile(name);
        }
        return new ImageTemplate(
                name,
                new Source(YamlConfiguration.loadConfiguration(selectedFile)),
                new Source(YamlConfiguration.loadConfiguration(
                        imageTemplateFile(ImageTemplate.DEFAULT_TEMPLATE))));
    }

    private void ensureImageTemplateFile(String name) {
        File file = imageTemplateFile(name);
        if (file.isFile()) {
            return;
        }
        try {
            plugin.saveResource(ImageTemplate.resourcePath(name), false);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Image template file does not exist: " + file, exception);
        }
    }

    private File imageTemplateFile(String name) {
        return new File(plugin.getDataFolder(), ImageTemplate.resourcePath(name));
    }

    private void ensureLanguageFile(String language) {
        File file = languageFile(language);
        if (file.isFile()) {
            return;
        }
        String resourcePath = Translations.resourcePath(language);
        try {
            plugin.saveResource(resourcePath, false);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Language file does not exist: " + file, exception);
        }
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
