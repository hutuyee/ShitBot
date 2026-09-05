package haaa.shitbotvelocity.config;

import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.console.ConsoleSettingsFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class VelocityConfigLoader {
    private final Path dataDirectory;
    private final ClassLoader classLoader;

    public VelocityConfigLoader(Path dataDirectory, ClassLoader classLoader) {
        this.dataDirectory = dataDirectory;
        this.classLoader = classLoader;
    }

    public Settings load() throws IOException {
        return SettingsFactory.create(loadSource("config.yml"));
    }

    public boolean isBStatsEnabled() throws IOException {
        return loadSource("config.yml").getBoolean("bstats.enabled", true);
    }

    public ConsoleSettings loadConsoleSettings() throws IOException {
        return ConsoleSettingsFactory.create(loadSource("commands.yml"));
    }

    private Source loadSource(String resourceName) throws IOException {
        Path configFile = ensureFile(resourceName);
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
        Object loaded;
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            loaded = yaml.load(reader);
        }
        Map<?, ?> root = loaded instanceof Map ? (Map<?, ?>) loaded : Collections.emptyMap();
        return new Source(root);
    }

    private Path ensureFile(String resourceName) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(resourceName);
        if (Files.isRegularFile(file)) {
            return file;
        }
        try (InputStream input = classLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Embedded " + resourceName + " is missing");
            }
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private static final class Source implements ConfigSource {
        private final Map<?, ?> root;

        private Source(Map<?, ?> root) {
            this.root = root;
        }

        @Override
        public String getString(String path, String fallback) {
            Object value = value(path);
            return value == null ? fallback : String.valueOf(value);
        }

        @Override
        public boolean getBoolean(String path, boolean fallback) {
            Object value = value(path);
            if (value instanceof Boolean) {
                return ((Boolean) value).booleanValue();
            }
            return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
        }

        @Override
        public int getInt(String path, int fallback) {
            Object value = value(path);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            try {
                return value == null ? fallback : Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        @Override
        public long getLong(String path, long fallback) {
            Object value = value(path);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            try {
                return value == null ? fallback : Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        @Override
        public List<String> getStringList(String path) {
            Object value = value(path);
            if (!(value instanceof Iterable)) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<String>();
            for (Object element : (Iterable<?>) value) {
                if (element != null && !String.valueOf(element).trim().isEmpty()) {
                    result.add(String.valueOf(element).trim());
                }
            }
            return result;
        }

        @Override
        public List<Long> getLongList(String path) {
            Object value = value(path);
            if (!(value instanceof Iterable)) {
                return Collections.emptyList();
            }
            List<Long> result = new ArrayList<Long>();
            for (Object element : (Iterable<?>) value) {
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
            Object value = value(path);
            if (!(value instanceof Map)) {
                return Collections.emptySet();
            }
            Set<String> result = new LinkedHashSet<String>();
            for (Object key : ((Map<?, ?>) value).keySet()) {
                if (key != null && !String.valueOf(key).trim().isEmpty()) {
                    result.add(String.valueOf(key).trim());
                }
            }
            return result;
        }

        private Object value(String path) {
            Object current = root;
            for (String part : path.split("\\.")) {
                if (!(current instanceof Map)) {
                    return null;
                }
                current = ((Map<?, ?>) current).get(part);
                if (current == null) {
                    return null;
                }
            }
            return current;
        }
    }
}
