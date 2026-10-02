package haaa.shitbot.renderer;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class YamlDocuments {
    private static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    private final Yaml loader;
    private final Yaml dumper;

    YamlDocuments() {
        LoaderOptions loadOptions = new LoaderOptions();
        loadOptions.setAllowDuplicateKeys(false);
        loadOptions.setMaxAliasesForCollections(20);
        loadOptions.setNestingDepthLimit(64);
        loadOptions.setCodePointLimit(MAX_DOCUMENT_BYTES);
        loader = new Yaml(new SafeConstructor(loadOptions));

        DumperOptions dumpOptions = new DumperOptions();
        dumpOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumpOptions.setPrettyFlow(true);
        dumpOptions.setIndent(2);
        dumpOptions.setIndicatorIndent(2);
        dumpOptions.setIndentWithIndicator(true);
        dumpOptions.setWidth(120);
        dumper = new Yaml(dumpOptions);
    }

    Map<String, Object> loadMap(Path file) throws IOException {
        return loadMap(file, false);
    }

    Map<String, Object> loadMap(Path file, boolean allowEmpty) throws IOException {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IOException("YAML file does not exist or is a symbolic link: " + file);
        }
        long size = Files.size(file);
        if ((!allowEmpty && size == 0L) || size > MAX_DOCUMENT_BYTES) {
            throw new IOException("YAML file has an invalid size: " + file.getFileName());
        }
        return loadMap(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), allowEmpty);
    }

    Map<String, Object> loadMap(String source) throws IOException {
        return loadMap(source, false);
    }

    private Map<String, Object> loadMap(String source, boolean allowEmpty) throws IOException {
        if (source == null || (!allowEmpty && source.isEmpty())
                || source.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IOException("YAML document has an invalid size");
        }
        final Object loaded;
        try {
            loaded = loader.load(source);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid YAML document: " + exception.getMessage(), exception);
        }
        if (loaded == null && allowEmpty) {
            return new LinkedHashMap<String, Object>();
        }
        if (!(loaded instanceof Map<?, ?>)) {
            throw new IOException("YAML document root must be a mapping");
        }
        return map((Map<?, ?>) loaded);
    }

    String dump(Map<String, Object> value) {
        return dumper.dump(value);
    }

    private Map<String, Object> map(Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
        }
        return result;
    }

    private Object normalize(Object value) {
        if (value instanceof Map<?, ?>) return map((Map<?, ?>) value);
        if (value instanceof Iterable<?>) {
            List<Object> values = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) values.add(normalize(item));
            return values;
        }
        return value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean ? value : String.valueOf(value);
    }
}
