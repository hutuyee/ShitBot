package haaa.shitbotbungee.config;

import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSettingsFactory;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public final class BungeeConfigLoader {
    private final Plugin plugin;

    public BungeeConfigLoader(Plugin plugin) {
        this.plugin = plugin;
    }

    public Settings load() throws IOException {
        File file = ensureConfigFile();
        Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        return SettingsFactory.create(new Source(configuration));
    }

    public boolean isBStatsEnabled() throws IOException {
        File file = ensureConfigFile();
        Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        return configuration.getBoolean("bstats.enabled", true);
    }

    public ConsoleSettings loadConsoleSettings() throws IOException {
        File file = ensureFile("commands.yml");
        Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        return ConsoleSettingsFactory.create(new Source(configuration));
    }

    private File ensureConfigFile() throws IOException {
        return ensureFile("config.yml");
    }

    private File ensureFile(String resourceName) throws IOException {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IOException("Cannot create plugin data directory: " + plugin.getDataFolder());
        }
        File file = new File(plugin.getDataFolder(), resourceName);
        if (file.isFile()) {
            return file;
        }
        try (InputStream input = plugin.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Embedded " + resourceName + " is missing");
            }
            Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private static final class Source implements ConfigSource {
        private final Configuration configuration;

        private Source(Configuration configuration) {
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
            Object value = configuration.get(path);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                }
            }
            return fallback;
        }

        @Override
        public List<String> getStringList(String path) {
            Object value = configuration.get(path);
            if (!(value instanceof Collection)) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<String>();
            for (Object element : (Collection<?>) value) {
                if (element != null && !String.valueOf(element).trim().isEmpty()) {
                    result.add(String.valueOf(element).trim());
                }
            }
            return result;
        }

        @Override
        public List<Long> getLongList(String path) {
            Object value = configuration.get(path);
            if (!(value instanceof Collection)) {
                return Collections.emptyList();
            }
            List<Long> result = new ArrayList<Long>();
            for (Object element : (Collection<?>) value) {
                if (element == null) {
                    continue;
                }
                try {
                    result.add(Long.valueOf(String.valueOf(element).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            return result;
        }

        @Override
        public Set<String> getSectionKeys(String path) {
            Configuration section = configuration.getSection(path);
            return section == null
                    ? Collections.<String>emptySet()
                    : new LinkedHashSet<String>(section.getKeys());
        }
    }
}
