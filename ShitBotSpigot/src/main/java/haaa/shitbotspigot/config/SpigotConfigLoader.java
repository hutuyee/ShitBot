package haaa.shitbotspigot.config;

import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
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
        return SettingsFactory.create(new Source(plugin.getConfig()));
    }

    public ConsoleSettings loadConsoleSettings() {
        File file = new File(plugin.getDataFolder(), "commands.yml");
        if (!file.isFile()) {
            plugin.saveResource("commands.yml", false);
        }
        return ConsoleSettingsFactory.create(new Source(YamlConfiguration.loadConfiguration(file)));
    }

    public boolean isBackendMode() {
        return "backend".equalsIgnoreCase(plugin.getConfig().getString("deployment.role", "standalone"));
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
