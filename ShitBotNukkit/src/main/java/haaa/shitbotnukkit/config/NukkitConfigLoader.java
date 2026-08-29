package haaa.shitbotnukkit.config;

import cn.nukkit.utils.Config;
import cn.nukkit.utils.ConfigSection;
import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSettingsFactory;
import haaa.shitbotnukkit.ShitBotNukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
        return SettingsFactory.create(new Source(configuration));
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
        return ConsoleSettingsFactory.create(new Source(configuration));
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
