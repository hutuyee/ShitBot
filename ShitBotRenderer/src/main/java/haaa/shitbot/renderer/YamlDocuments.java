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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class YamlDocuments {
    private static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    private static final int MAX_DOCUMENT_DEPTH = 64;
    private static final int MAX_DOCUMENT_NODES = 65536;
    private final Yaml loader;
    private final Yaml dumper;

    YamlDocuments() {
        LoaderOptions loadOptions = new LoaderOptions();
        loadOptions.setAllowDuplicateKeys(false);
        loadOptions.setMaxAliasesForCollections(20);
        loadOptions.setNestingDepthLimit(MAX_DOCUMENT_DEPTH);
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

    private synchronized Map<String, Object> loadMap(String source, boolean allowEmpty) throws IOException {
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
        return map((Map<?, ?>) loaded, new NormalizationBudget(), 0);
    }

    synchronized String dump(Map<String, Object> value) {
        return dumper.dump(value);
    }

    private Map<String, Object> map(Map<?, ?> input, NormalizationBudget budget, int depth) throws IOException {
        budget.enter(input, depth);
        try {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                Object key = entry.getKey();
                if (key instanceof Map<?, ?> || key instanceof Iterable<?>) {
                    throw new IOException("YAML mapping keys must be scalar values");
                }
                budget.visit(depth + 1);
                if (key != null) {
                    String name = String.valueOf(key);
                    if (result.containsKey(name)) {
                        throw new IOException("YAML mapping keys collide after normalization");
                    }
                    result.put(name, normalize(entry.getValue(), budget, depth + 1));
                }
            }
            return result;
        } finally {
            budget.leave(input);
        }
    }

    private Object normalize(Object value, NormalizationBudget budget, int depth) throws IOException {
        if (value instanceof Map<?, ?>) return map((Map<?, ?>) value, budget, depth);
        if (value instanceof Iterable<?>) {
            budget.enter(value, depth);
            try {
                List<Object> values = new ArrayList<Object>();
                for (Object item : (Iterable<?>) value) {
                    values.add(normalize(item, budget, depth + 1));
                }
                return values;
            } finally {
                budget.leave(value);
            }
        }
        budget.visit(depth);
        return value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean ? value : String.valueOf(value);
    }

    private static final class NormalizationBudget {
        private final IdentityHashMap<Object, Boolean> ancestors = new IdentityHashMap<Object, Boolean>();
        private int nodes;

        private void visit(int depth) throws IOException {
            if (depth > MAX_DOCUMENT_DEPTH || ++nodes > MAX_DOCUMENT_NODES) {
                throw new IOException("Expanded YAML document exceeds its structure limit");
            }
        }

        private void enter(Object value, int depth) throws IOException {
            visit(depth);
            if (ancestors.put(value, Boolean.TRUE) != null) {
                throw new IOException("Recursive YAML aliases are not supported");
            }
        }

        private void leave(Object value) {
            ancestors.remove(value);
        }
    }
}
