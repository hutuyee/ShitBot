package haaa.shitbot.core.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves item icons from exported PNG overrides, resource packs and mod JAR assets.
 *
 * <p>Exported icons are authoritative and support every custom renderer. Automatic
 * model parsing intentionally targets generated/handheld item models and common
 * CustomModelData overrides. Complex 3D/dynamic models fall back to the missing
 * texture instead of pretending to render them correctly.</p>
 */
public final class ItemIconResolver {
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int MAX_MODEL_DEPTH = 16;

    private final Settings.Inventory settings;
    private final PlatformBridge platform;
    private final Path dataDirectory;
    private final Path exportedDirectory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<CompletableFuture<ResourceIndex>> indexFuture =
            new AtomicReference<CompletableFuture<ResourceIndex>>();
    private final Map<String, CacheValue> cache;

    public ItemIconResolver(Settings.Inventory settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
        this.dataDirectory = platform.getDataDirectory().toAbsolutePath().normalize();
        this.exportedDirectory = resolve(settings.getExportedIconsDirectory());
        final int maximumEntries = settings.getIconCacheEntries();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheValue>(64, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheValue> eldest) {
                return size() > maximumEntries;
            }
        });
    }

    public CompletableFuture<Void> warmUpAsync(Executor executor) {
        return getIndexFuture(executor).thenApply(new java.util.function.Function<ResourceIndex, Void>() {
            @Override
            public Void apply(ResourceIndex ignored) {
                return null;
            }
        });
    }

    public BufferedImage resolve(InventorySnapshot.Item item) {
        if (item == null) {
            return null;
        }
        String cacheKey = item.getRegistryId() + "#" +
                (item.getCustomModelData() == null ? "" : item.getCustomModelData().toString());
        CacheValue cached = cache.get(cacheKey);
        if (cached != null) {
            return cached.image;
        }

        BufferedImage image = loadExported(item);
        if (image == null) {
            CompletableFuture<ResourceIndex> future = indexFuture.get();
            if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
                return null;
            }
            try {
                image = loadFromResources(item, future.getNow(ResourceIndex.empty()));
            } catch (Throwable throwable) {
                platform.warn("Failed to resolve item icon " + item.getRegistryId() + ": "
                        + throwable.getMessage());
            }
        }
        cache.put(cacheKey, new CacheValue(image));
        return image;
    }

    private BufferedImage loadExported(InventorySnapshot.Item item) {
        Identifier id = Identifier.parse(item.getRegistryId(), "minecraft");
        if (id == null) {
            return null;
        }
        Path namespaceDirectory = exportedDirectory.resolve(id.namespace).normalize();
        if (!namespaceDirectory.startsWith(exportedDirectory)) {
            return null;
        }
        String suffix = item.getCustomModelData() == null
                ? null : "__cmd_" + item.getCustomModelData().intValue();
        if (suffix != null) {
            BufferedImage exact = readImage(namespaceDirectory.resolve(id.path + suffix + ".png"));
            if (exact != null) {
                return exact;
            }
        }
        return readImage(namespaceDirectory.resolve(id.path + ".png"));
    }

    private BufferedImage loadFromResources(InventorySnapshot.Item item, ResourceIndex index) throws IOException {
        Identifier itemId = Identifier.parse(item.getRegistryId(), "minecraft");
        if (itemId == null) {
            return null;
        }
        Identifier modelId = resolveItemDefinition(itemId, index);
        if (modelId == null) {
            modelId = new Identifier(itemId.namespace, "item/" + itemId.path);
        }
        Model model = loadModel(modelId, item.getCustomModelData(), index, 0);
        if (model == null) {
            return null;
        }
        if (!model.flat) {
            return null;
        }
        String textureReference = model.textures.get("layer0");
        textureReference = dereference(textureReference, model.textures, 0);
        Identifier textureId = Identifier.parse(textureReference, modelId.namespace);
        if (textureId == null) {
            return null;
        }
        Resource resource = index.resources.get("assets/" + textureId.namespace
                + "/textures/" + textureId.path + ".png");
        if (resource == null) {
            return null;
        }
        byte[] bytes = resource.read(MAX_JSON_BYTES * 8);
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private Identifier resolveItemDefinition(Identifier itemId, ResourceIndex index) throws IOException {
        Resource resource = index.resources.get("assets/" + itemId.namespace + "/items/" + itemId.path + ".json");
        if (resource == null) {
            return null;
        }
        JsonNode root = mapper.readTree(resource.read(MAX_JSON_BYTES));
        String model = findFirstModelReference(root, 0);
        return Identifier.parse(model, itemId.namespace);
    }

    private String findFirstModelReference(JsonNode node, int depth) {
        if (node == null || depth > MAX_MODEL_DEPTH) {
            return null;
        }
        if (node.isObject()) {
            String type = node.path("type").asText("");
            JsonNode modelNode = node.get("model");
            if (modelNode != null && modelNode.isTextual()
                    && (type.isEmpty() || type.endsWith(":model") || "model".equals(type))) {
                return modelNode.asText();
            }
            if (modelNode != null && modelNode.isObject()) {
                String nested = findFirstModelReference(modelNode, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if ("model".equals(entry.getKey())) {
                    continue;
                }
                String nested = findFirstModelReference(entry.getValue(), depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findFirstModelReference(child, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private Model loadModel(Identifier modelId,
                            Integer customModelData,
                            ResourceIndex index,
                            int depth) throws IOException {
        if (modelId == null || depth > MAX_MODEL_DEPTH) {
            return null;
        }
        Resource resource = index.resources.get("assets/" + modelId.namespace
                + "/models/" + modelId.path + ".json");
        if (resource == null) {
            return null;
        }
        JsonNode root = mapper.readTree(resource.read(MAX_JSON_BYTES));

        Identifier selectedOverride = selectCustomModelOverride(root, customModelData, modelId.namespace);
        if (selectedOverride != null && !selectedOverride.equals(modelId)) {
            Model selected = loadModel(selectedOverride, null, index, depth + 1);
            if (selected != null) {
                return selected;
            }
        }

        Map<String, String> textures = new HashMap<String, String>();
        boolean flat = false;
        String parentText = root.path("parent").asText("");
        Identifier parentId = Identifier.parse(parentText, modelId.namespace);
        if (parentId != null) {
            if (isBuiltinParent(parentId)) {
                flat = isFlatBuiltinParent(parentId);
            } else {
                Model parent = loadModel(parentId, null, index, depth + 1);
                if (parent != null) {
                    textures.putAll(parent.textures);
                    flat = parent.flat;
                }
            }
        }
        JsonNode textureNode = root.path("textures");
        if (textureNode.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = textureNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getValue().isTextual()) {
                    textures.put(entry.getKey(), entry.getValue().asText());
                }
            }
        }
        if (root.has("elements")) {
            flat = false;
        } else if (parentId == null && textures.containsKey("layer0")) {
            flat = true;
        }
        return new Model(textures, flat);
    }

    private Identifier selectCustomModelOverride(JsonNode root, Integer customModelData, String defaultNamespace) {
        if (customModelData == null) {
            return null;
        }
        Identifier selected = null;
        JsonNode overrides = root.path("overrides");
        if (!overrides.isArray()) {
            return null;
        }
        for (JsonNode override : overrides) {
            JsonNode predicate = override.path("predicate");
            JsonNode custom = predicate.get("custom_model_data");
            if (custom == null) {
                custom = predicate.get("minecraft:custom_model_data");
            }
            if (custom != null && custom.isNumber()
                    && customModelData.intValue() >= custom.asDouble()) {
                Identifier candidate = Identifier.parse(override.path("model").asText(""), defaultNamespace);
                if (candidate != null) {
                    selected = candidate;
                }
            }
        }
        return selected;
    }

    private boolean isBuiltinParent(Identifier id) {
        return "minecraft".equals(id.namespace)
                && ("item/generated".equals(id.path)
                || "item/handheld".equals(id.path)
                || id.path.startsWith("builtin/"));
    }

    private boolean isFlatBuiltinParent(Identifier id) {
        return "minecraft".equals(id.namespace)
                && ("item/generated".equals(id.path) || "item/handheld".equals(id.path));
    }

    private String dereference(String value, Map<String, String> textures, int depth) {
        if (value == null || depth > MAX_MODEL_DEPTH) {
            return null;
        }
        if (!value.startsWith("#")) {
            return value;
        }
        return dereference(textures.get(value.substring(1)), textures, depth + 1);
    }

    private CompletableFuture<ResourceIndex> getIndexFuture(Executor executor) {
        CompletableFuture<ResourceIndex> existing = indexFuture.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<ResourceIndex> created = CompletableFuture.supplyAsync(
                new java.util.function.Supplier<ResourceIndex>() {
                    @Override
                    public ResourceIndex get() {
                        try {
                            return buildIndex();
                        } catch (Throwable throwable) {
                            platform.warn("Inventory resource index failed: " + throwable.getMessage());
                            return ResourceIndex.empty();
                        }
                    }
                }, executor);
        if (indexFuture.compareAndSet(null, created)) {
            return created;
        }
        return indexFuture.get();
    }

    private ResourceIndex buildIndex() throws IOException {
        final Map<String, Resource> resources = new LinkedHashMap<String, Resource>();
        List<Path> sources = new ArrayList<Path>();
        for (String configured : settings.getResourceArchives()) {
            Path path = resolve(configured);
            if (Files.exists(path)) {
                sources.add(path);
            }
        }
        if (settings.isScanModJars()) {
            Path modsDirectory = resolve(settings.getModsDirectory());
            if (Files.isDirectory(modsDirectory)) {
                List<Path> modArchives = new ArrayList<Path>();
                try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(modsDirectory)) {
                    for (Path path : stream) {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (Files.isRegularFile(path) && (name.endsWith(".jar") || name.endsWith(".zip"))) {
                            modArchives.add(path);
                        }
                    }
                }
                Collections.sort(modArchives, new Comparator<Path>() {
                    @Override
                    public int compare(Path left, Path right) {
                        return left.toString().compareToIgnoreCase(right.toString());
                    }
                });
                sources.addAll(modArchives);
            }
        }
        for (Path source : sources) {
            if (Files.isDirectory(source)) {
                indexDirectory(source, resources);
            } else {
                indexArchive(source, resources);
            }
        }
        platform.info("Inventory icon resource index contains " + resources.size()
                + " assets from " + sources.size() + " sources");
        return new ResourceIndex(resources);
    }

    private void indexDirectory(final Path root, final Map<String, Resource> resources) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = normalizeResourcePath(root.relativize(file).toString());
                if (isSupportedResource(relative) && !resources.containsKey(relative)) {
                    resources.put(relative, Resource.file(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void indexArchive(Path archive, Map<String, Resource> resources) throws IOException {
        ZipFile zip = new ZipFile(archive.toFile());
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeResourcePath(entry.getName());
                if (isSupportedResource(name) && !resources.containsKey(name)) {
                    resources.put(name, Resource.archive(archive, entry.getName()));
                }
            }
        } finally {
            zip.close();
        }
    }

    private boolean isSupportedResource(String path) {
        return path.startsWith("assets/") && (path.endsWith(".json") || path.endsWith(".png"));
    }

    private BufferedImage readImage(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(exportedDirectory) || !Files.isRegularFile(normalized)) {
                return null;
            }
            return ImageIO.read(normalized.toFile());
        } catch (Throwable throwable) {
            platform.warn("Failed to read exported inventory icon " + path + ": " + throwable.getMessage());
            return null;
        }
    }

    private Path resolve(String configured) {
        String value = configured == null ? "" : configured.trim();
        Path path = java.nio.file.Paths.get(value.isEmpty() ? "." : value);
        return path.isAbsolute() ? path.normalize() : dataDirectory.resolve(path).normalize();
    }

    private static String normalizeResourcePath(String value) {
        return value.replace('\\', '/').replaceAll("^/+", "");
    }

    private static final class CacheValue {
        private final BufferedImage image;

        private CacheValue(BufferedImage image) {
            this.image = image;
        }
    }

    private static final class Model {
        private final Map<String, String> textures;
        private final boolean flat;

        private Model(Map<String, String> textures, boolean flat) {
            this.textures = textures;
            this.flat = flat;
        }
    }

    private static final class ResourceIndex {
        private final Map<String, Resource> resources;

        private ResourceIndex(Map<String, Resource> resources) {
            this.resources = Collections.unmodifiableMap(new LinkedHashMap<String, Resource>(resources));
        }

        private static ResourceIndex empty() {
            return new ResourceIndex(Collections.<String, Resource>emptyMap());
        }
    }

    private static final class Resource {
        private final Path file;
        private final Path archive;
        private final String entry;

        private Resource(Path file, Path archive, String entry) {
            this.file = file;
            this.archive = archive;
            this.entry = entry;
        }

        private static Resource file(Path path) {
            return new Resource(path.toAbsolutePath().normalize(), null, null);
        }

        private static Resource archive(Path archive, String entry) {
            return new Resource(null, archive.toAbsolutePath().normalize(), entry);
        }

        private byte[] read(int maximumBytes) throws IOException {
            if (file != null) {
                long size = Files.size(file);
                if (size < 0L || size > maximumBytes) {
                    throw new IOException("Resource exceeds safety limit: " + file);
                }
                return Files.readAllBytes(file);
            }
            ZipFile zip = new ZipFile(archive.toFile());
            try {
                ZipEntry zipEntry = zip.getEntry(entry);
                if (zipEntry == null || zipEntry.isDirectory()
                        || zipEntry.getSize() > maximumBytes) {
                    throw new IOException("Invalid archive resource: " + entry);
                }
                InputStream input = zip.getInputStream(zipEntry);
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > maximumBytes) {
                            throw new IOException("Archive resource exceeds safety limit: " + entry);
                        }
                        output.write(buffer, 0, read);
                    }
                    return output.toByteArray();
                } finally {
                    input.close();
                }
            } finally {
                zip.close();
            }
        }
    }

    private static final class Identifier {
        private final String namespace;
        private final String path;

        private Identifier(String namespace, String path) {
            this.namespace = namespace;
            this.path = path;
        }

        private static Identifier parse(String value, String defaultNamespace) {
            if (value == null) {
                return null;
            }
            String clean = value.trim().toLowerCase(Locale.ROOT);
            if (clean.isEmpty() || clean.startsWith("#")) {
                return null;
            }
            int colon = clean.indexOf(':');
            String namespace = colon < 0 ? defaultNamespace : clean.substring(0, colon);
            String path = colon < 0 ? clean : clean.substring(colon + 1);
            if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+")
                    || path.contains("..")) {
                return null;
            }
            return new Identifier(namespace, path);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Identifier)) {
                return false;
            }
            Identifier value = (Identifier) other;
            return namespace.equals(value.namespace) && path.equals(value.path);
        }

        @Override
        public int hashCode() {
            return 31 * namespace.hashCode() + path.hashCode();
        }
    }
}
