package haaa.shitbot.core.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Restores bundled configuration files and persists missing YAML defaults. */
public final class ConfigResources {
    private final Path directory;
    private final Function<String, InputStream> resources;

    public ConfigResources(Path directory, Function<String, InputStream> resources) {
        this.directory = directory.toAbsolutePath().normalize();
        this.resources = resources;
    }

    /** Restores missing/empty files, leaving nonempty legacy files ready for migration. */
    public Path ensure(String resource) throws IOException {
        Path file = resolve(resource);
        if (!Files.exists(file)) {
            writeAtomically(file, defaults(resource));
        } else if (readMapping(Files.readAllBytes(file), file.toString()).getValue().isEmpty()) {
            return complete(resource);
        }
        return file;
    }

    public Path complete(String resource) throws IOException {
        Path file = resolve(resource);
        byte[] defaults = defaults(resource);
        MappingNode fallback = readMapping(defaults, "defaults for " + resource);
        if (!Files.exists(file)) {
            writeAtomically(file, defaults);
            return file;
        }
        byte[] contents = Files.readAllBytes(file);
        MappingNode current = readMapping(contents, file.toString());
        if (merge(current, fallback)) {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setProcessComments(true);
            options.setIndent(2);
            options.setIndicatorIndent(2);
            options.setIndentWithIndicator(true);
            options.setSplitLines(false);
            if (new String(contents, StandardCharsets.UTF_8).contains("\r\n")) {
                options.setLineBreak(DumperOptions.LineBreak.WIN);
            }
            StringWriter writer = new StringWriter();
            new Yaml(options).serialize(current, writer);
            writeAtomically(file, writer.toString().getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private Path resolve(String resource) throws IOException {
        Path file = directory.resolve(resource).normalize();
        if (!file.startsWith(directory) || file.equals(directory)) {
            throw new IOException("Configuration path escapes the data directory: " + resource);
        }
        return file;
    }

    private byte[] defaults(String resource) throws IOException {
        try (InputStream input = resources.apply(resource)) {
            if (input != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
        String fallback;
        if (resource.startsWith("lang/") && !resource.equals(Translations.resourcePath(Translations.DEFAULT_LANGUAGE))) {
            fallback = Translations.resourcePath(Translations.DEFAULT_LANGUAGE);
        } else if (resource.startsWith("templates/") && !resource.equals(ImageTemplate.resourcePath(ImageTemplate.DEFAULT_TEMPLATE))) {
            fallback = ImageTemplate.resourcePath(ImageTemplate.DEFAULT_TEMPLATE);
        } else {
            throw new IOException("Embedded " + resource + " is missing");
        }
        return Files.readAllBytes(complete(fallback));
    }

    private MappingNode readMapping(byte[] bytes, String name) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(64);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        String source = new String(bytes, StandardCharsets.UTF_8);
        try {
            // Construction validates duplicate keys and tags; composition retains comments.
            Object loaded = yaml.load(source);
            if (loaded != null && !(loaded instanceof Map<?, ?>)) {
                throw new IOException("YAML root must be a mapping: " + name);
            }
            options.setProcessComments(true);
            Node node = new Yaml(new SafeConstructor(options)).compose(new StringReader(source));
            if (node instanceof MappingNode && Tag.MAP.equals(node.getTag())) {
                return (MappingNode) node;
            }
            MappingNode empty = new MappingNode(Tag.MAP, new ArrayList<NodeTuple>(), DumperOptions.FlowStyle.BLOCK);
            if (node != null) {
                copyComments(node, empty);
            }
            return empty;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid YAML configuration: " + name, exception);
        }
    }

    private boolean merge(MappingNode current, MappingNode defaults) {
        boolean changed = false;
        for (NodeTuple entry : defaults.getValue()) {
            Node key = entry.getKeyNode();
            if (!(key instanceof ScalarNode) || Tag.MERGE.equals(key.getTag())) {
                continue;
            }
            Node value = find(current, (ScalarNode) key,
                    Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
            Node fallback = entry.getValueNode();
            if (value == null || (Tag.NULL.equals(value.getTag()) && !Tag.NULL.equals(fallback.getTag()))) {
                boolean replaced = false;
                for (int index = 0; index < current.getValue().size(); index++) {
                    NodeTuple existing = current.getValue().get(index);
                    if (sameKey(existing.getKeyNode(), (ScalarNode) key)) {
                        copyComments(existing.getValueNode(), fallback);
                        current.getValue().set(index, new NodeTuple(existing.getKeyNode(), fallback));
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    current.getValue().add(entry);
                }
                changed = true;
            } else if (value instanceof MappingNode && fallback instanceof MappingNode) {
                changed |= merge((MappingNode) value, (MappingNode) fallback);
            }
            // Lists and explicit scalar values belong to the administrator, including [] and "".
        }
        if (changed) {
            current.setFlowStyle(DumperOptions.FlowStyle.BLOCK);
        }
        return changed;
    }

    private Node find(Node node, ScalarNode key, Set<Node> visited) {
        if (!visited.add(node)) {
            return null;
        }
        if (node instanceof MappingNode) {
            MappingNode mapping = (MappingNode) node;
            for (NodeTuple entry : mapping.getValue()) {
                if (sameKey(entry.getKeyNode(), key)) {
                    return entry.getValueNode();
                }
            }
            for (NodeTuple entry : mapping.getValue()) {
                if (Tag.MERGE.equals(entry.getKeyNode().getTag())) {
                    Node inherited = find(entry.getValueNode(), key, visited);
                    if (inherited != null) {
                        return inherited;
                    }
                }
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode) node).getValue()) {
                Node inherited = find(element, key, visited);
                if (inherited != null) {
                    return inherited;
                }
            }
        }
        return null;
    }

    private boolean sameKey(Node node, ScalarNode key) {
        return node instanceof ScalarNode && node.getTag().equals(key.getTag())
                && ((ScalarNode) node).getValue().equals(key.getValue());
    }

    private void copyComments(Node source, Node destination) {
        if (source.getBlockComments() != null && !source.getBlockComments().isEmpty()) {
            destination.setBlockComments(source.getBlockComments());
        }
        if (source.getInLineComments() != null && !source.getInLineComments().isEmpty()) {
            destination.setInLineComments(source.getInLineComments());
        }
        if (source.getEndComments() != null && !source.getEndComments().isEmpty()) {
            destination.setEndComments(source.getEndComments());
        }
    }

    private void writeAtomically(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
