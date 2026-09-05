package haaa.shitbotvelocity.config;

import haaa.shitbot.core.config.ConfigSource;
import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.SettingsFactory;
import haaa.shitbot.core.config.Translations;
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
        Source source = loadSource("config.yml");
        return SettingsFactory.create(
                source,
                loadTranslations(source),
                loadImageTemplate(source.getString("image.template", ImageTemplate.DEFAULT_TEMPLATE)),
                loadImageTemplate(source.getString("inventory.template", ImageTemplate.DEFAULT_TEMPLATE)));
    }

    public boolean isBStatsEnabled() throws IOException {
        return loadSource("config.yml").getBoolean("bstats.enabled", true);
    }

    public ConsoleSettings loadConsoleSettings() throws IOException {
        Source config = loadSource("config.yml");
        return ConsoleSettingsFactory.create(loadSource("commands.yml"), loadTranslations(config));
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
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream input = classLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Embedded " + resourceName + " is missing");
            }
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private Translations loadTranslations(Source config) throws IOException {
        Path fallbackFile = ensureFile(Translations.resourcePath(Translations.DEFAULT_LANGUAGE));
        ensureFile(Translations.resourcePath("en_US"));
        String language = Translations.normalizeLanguage(config.getString("language", Translations.DEFAULT_LANGUAGE));
        Path selectedFile = dataDirectory.resolve(Translations.resourcePath(language));
        if (!Files.isRegularFile(selectedFile)) {
            selectedFile = ensureFile(Translations.resourcePath(language));
        }
        return new Translations(language, loadSource(selectedFile), loadSource(fallbackFile));
    }

    private ImageTemplate loadImageTemplate(String configuredName) throws IOException {
        Path fallbackFile = ensureFile(ImageTemplate.resourcePath(ImageTemplate.DEFAULT_TEMPLATE));
        String name = ImageTemplate.normalizeName(configuredName);
        Path selectedFile = dataDirectory.resolve(ImageTemplate.resourcePath(name));
        if (!Files.isRegularFile(selectedFile)) {
            selectedFile = ensureFile(ImageTemplate.resourcePath(name));
        }
        return new ImageTemplate(name, loadSource(selectedFile), loadSource(fallbackFile));
    }

    private Source loadSource(Path file) throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
        Object loaded;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            loaded = yaml.load(reader);
        }
        Map<?, ?> root = loaded instanceof Map ? (Map<?, ?>) loaded : Collections.emptyMap();
        return new Source(root);
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
