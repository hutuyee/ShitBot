package haaa.shitbotnukkit.config;

import cn.nukkit.utils.Config;
import cn.nukkit.utils.ConfigSection;
import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.LegacyLanguageMigration;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSettingsFactory;
import haaa.shitbotnukkit.ShitBotNukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NukkitConfigLoader {
    private final ShitBotNukkit plugin;

    public NukkitConfigLoader(ShitBotNukkit plugin) {
        this.plugin = plugin;
    }

    public Settings load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        Config configuration = plugin.getConfig();
        if (!configuration.isCorrect()) {
            throw new IllegalStateException("config.yml is not a valid YAML configuration");
        }
        Source source = new Source(configuration);
        return SettingsFactory.create(
                source,
                loadTranslations(source),
                loadImageTemplate(source.getString("image.template", ImageTemplate.DEFAULT_TEMPLATE)),
                loadImageTemplate(source.getString("inventory.template", ImageTemplate.DEFAULT_TEMPLATE)));
    }

    public ConsoleSettings loadConsoleSettings() {
        File file = new File(plugin.getDataFolder(), "commands.yml");
        if (!file.isFile()) {
            if (!plugin.saveResource("commands.yml", false)) {
                throw new IllegalStateException("Unable to create commands.yml");
            }
        }
        Config configuration = new Config(file, Config.YAML);
        if (!configuration.isCorrect()) {
            throw new IllegalStateException("commands.yml is not a valid YAML configuration");
        }
        return ConsoleSettingsFactory.create(
                new Source(configuration),
                loadTranslations(new Source(plugin.getConfig())));
    }

    private Translations loadTranslations(Source config) {
        File fallbackFile = ensureLanguageFile(Translations.DEFAULT_LANGUAGE);
        ensureLanguageFile("en_US");
        migrateLegacyLanguage(config, fallbackFile);
        String language = Translations.normalizeLanguage(config.getString("language", Translations.DEFAULT_LANGUAGE));
        File selectedFile = languageFile(language);
        if (!selectedFile.isFile()) {
            selectedFile = ensureLanguageFile(language);
        }
        return new Translations(
                language,
                loadLanguageSource(selectedFile),
                loadLanguageSource(fallbackFile));
    }

    private void migrateLegacyLanguage(Source config, File fallbackFile) {
        Config language = new Config(fallbackFile, Config.YAML);
        if (!language.isCorrect()) {
            throw new IllegalStateException(fallbackFile.getName() + " is not a valid YAML configuration");
        }
        if (!LegacyLanguageMigration.isRequired(config, new Source(language))) {
            return;
        }
        for (Map.Entry<String, Object> entry : LegacyLanguageMigration.collect(config).entrySet()) {
            language.set(entry.getKey(), entry.getValue());
        }
        language.set(LegacyLanguageMigration.MARKER_PATH, true);
        if (!language.save()) {
            throw new IllegalStateException("Unable to migrate legacy messages to " + fallbackFile);
        }
    }

    private ImageTemplate loadImageTemplate(String configuredName) {
        File fallbackFile = ensureImageTemplateFile(ImageTemplate.DEFAULT_TEMPLATE);
        String name = ImageTemplate.normalizeName(configuredName);
        File selectedFile = imageTemplateFile(name);
        if (!selectedFile.isFile()) {
            selectedFile = ensureImageTemplateFile(name);
        }
        return new ImageTemplate(name, loadImageTemplateSource(selectedFile), loadImageTemplateSource(fallbackFile));
    }

    private Source loadImageTemplateSource(File file) {
        Config configuration = new Config(file, Config.YAML);
        if (!configuration.isCorrect()) {
            throw new IllegalStateException(file.getName() + " is not a valid YAML image template");
        }
        return new Source(configuration);
    }

    private File ensureImageTemplateFile(String name) {
        File file = imageTemplateFile(name);
        if (file.isFile()) {
            return file;
        }
        if (!plugin.saveResource(ImageTemplate.resourcePath(name), false) || !file.isFile()) {
            throw new IllegalStateException("Image template file does not exist: " + file);
        }
        return file;
    }

    private File imageTemplateFile(String name) {
        return new File(plugin.getDataFolder(), ImageTemplate.resourcePath(name));
    }

    private Source loadLanguageSource(File file) {
        Config configuration = new Config(file, Config.YAML);
        if (!configuration.isCorrect()) {
            throw new IllegalStateException(file.getName() + " is not a valid YAML configuration");
        }
        return new Source(configuration);
    }

    private File ensureLanguageFile(String language) {
        File file = languageFile(language);
        if (file.isFile()) {
            return file;
        }
        String resourcePath = Translations.resourcePath(language);
        if (!plugin.saveResource(resourcePath, false) || !file.isFile()) {
            throw new IllegalStateException("Language file does not exist: " + file);
        }
        return file;
    }

    private File languageFile(String language) {
        return new File(plugin.getDataFolder(), Translations.resourcePath(language));
    }

    private static final class Source implements ConfigSource {
        private final Config configuration;

        private Source(Config configuration) {
            this.configuration = configuration;
        }

        @Override
        public String getString(String path, String fallback) {
            return configuration.getString(path, fallback);
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
            return values == null ? Collections.<String>emptyList() : new ArrayList<String>(values);
        }

        @Override
        public List<Long> getLongList(String path) {
            List<Long> values = configuration.getLongList(path);
            return values == null ? Collections.<Long>emptyList() : new ArrayList<Long>(values);
        }

        @Override
        public Set<String> getSectionKeys(String path) {
            ConfigSection section = configuration.getSection(path);
            return section == null
                    ? Collections.<String>emptySet()
                    : new LinkedHashSet<String>(section.keySet());
        }
    }
}
