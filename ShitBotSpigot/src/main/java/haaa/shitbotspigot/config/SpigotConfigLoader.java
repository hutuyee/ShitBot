package haaa.shitbotspigot.config;

import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SpigotConfigLoader {
    private final JavaPlugin plugin;

    public SpigotConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Settings load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        return SettingsFactory.create(new Source(plugin.getConfig()));
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
    }
}
