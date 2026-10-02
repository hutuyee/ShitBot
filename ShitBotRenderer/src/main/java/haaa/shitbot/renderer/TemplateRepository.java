package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageDataProviderSpec;
import haaa.shitbot.api.ImageTemplateInfo;
import haaa.shitbot.api.spi.ImageTemplateEngineHost;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TemplateRepository {
    private static final Set<String> NODE_TYPES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("text", "image", "avatar", "rectangle", "circle",
                    "line", "progress", "group", "stack", "grid", "condition", "loop")));
    private static final int MAX_PROVIDER_DECLARATIONS = 64;
    private static final int MAX_TREE_DEPTH = 32;

    private final ImageTemplateEngineSettings settings;
    private final ImageTemplateEngineHost host;
    private final Path root;
    private final YamlDocuments yaml = new YamlDocuments();
    private final Map<String, TemplateSnapshot> snapshotCache =
            new ConcurrentHashMap<String, TemplateSnapshot>();
    private volatile List<ImageTemplateInfo> publishedTemplates = Collections.emptyList();

    TemplateRepository(ImageTemplateEngineSettings settings, ImageTemplateEngineHost host) {
        this.settings = settings;
        this.host = host;
        this.root = settings.getTemplatesDirectory().toAbsolutePath().normalize();
    }

    synchronized void initialize() throws IOException {
        Files.createDirectories(root);
        requireDirectory(root);
        installExample();
        Path example = templateDirectory("online-status");
        if (Files.isSymbolicLink(example.resolve("published.yml"))) {
            throw new IOException("Published template pointer cannot be a symbolic link");
        }
        if (!Files.exists(example.resolve("published.yml"))) {
            if (Files.exists(example.resolve("versions"))) requireDirectory(example.resolve("versions"));
            List<Long> versions = listVersions("online-status");
            if (versions.isEmpty()) {
                publish("online-status");
            } else {
                // Recover the pointer without publishing possibly unfinished draft edits.
                rollback("online-status", versions.get(0).longValue());
            }
        }
        refresh();
    }

    List<ImageTemplateInfo> getPublishedTemplates() {
        return publishedTemplates;
    }

    synchronized List<String> listTemplateIds() throws IOException {
        List<String> ids = new ArrayList<String>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry) || Files.isSymbolicLink(entry)) continue;
                try {
                    ids.add(templateId(entry.getFileName().toString()));
                } catch (IOException ignored) {
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    synchronized void createDraft(String id, String name) throws IOException {
        String normalized = templateId(id);
        Path template = templateDirectory(normalized);
        if (Files.exists(template)) throw new IOException("Template already exists: " + normalized);
        try {
            Files.createDirectories(template.resolve("assets"));
            Map<String, Object> manifest = yaml.loadMap(resourceText("/defaults/online-status/manifest.yml"));
            Map<String, Object> scene = yaml.loadMap(resourceText("/defaults/online-status/scene.yml"));
            manifest.put("id", normalized);
            manifest.put("name", name == null || name.trim().isEmpty() ? normalized : name.trim());
            manifest.put("suggested-file-name", normalized + ".png");
            writeAtomic(template.resolve("manifest.yml"), yaml.dump(manifest).getBytes(StandardCharsets.UTF_8));
            writeAtomic(template.resolve("scene.yml"), yaml.dump(scene).getBytes(StandardCharsets.UTF_8));
            validateSnapshot(template, normalized, 1L);
        } catch (IOException exception) {
            deleteTreeQuietly(template);
            throw exception;
        }
    }

    Optional<ImageTemplateInfo> findInfo(String id) {
        String normalized;
        try {
            normalized = templateId(id);
        } catch (IOException ignored) {
            return Optional.empty();
        }
        for (ImageTemplateInfo info : publishedTemplates) {
            if (info.getId().equals(normalized)) return Optional.of(info);
        }
        return Optional.empty();
    }

    synchronized TemplateSnapshot loadPublished(String id) throws IOException {
        Path template = templateDirectory(id);
        Map<String, Object> pointer = yaml.loadMap(template.resolve("published.yml"));
        long version = longValue(pointer.get("version"), 0L);
        String digest = string(pointer.get("sha256"));
        if (version < 1L || !digest.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Template has an invalid published pointer: " + id);
        }
        String cacheKey = templateId(id) + '@' + version + ':' + digest.toLowerCase(Locale.ROOT);
        TemplateSnapshot cached = snapshotCache.get(cacheKey);
        if (cached != null) return cached;
        Path versionDirectory = template.resolve("versions").resolve(String.valueOf(version)).normalize();
        requireWithin(template, versionDirectory);
        requireDirectory(versionDirectory);
        if (!digest.equalsIgnoreCase(digestTree(versionDirectory))) {
            throw new IOException("Published template snapshot was modified: " + id + " v" + version);
        }
        TemplateSnapshot loaded = validateSnapshot(versionDirectory, templateId(id), version);
        snapshotCache.put(cacheKey, loaded);
        return loaded;
    }

    synchronized long publish(String id) throws IOException {
        String normalized = templateId(id);
        Path template = templateDirectory(normalized);
        requireDirectory(template);
        TemplateSnapshot draft = validateSnapshot(template, normalized, 1L);
        long version = nextVersion(template.resolve("versions"));
        Path versions = template.resolve("versions");
        Files.createDirectories(versions);
        Path temporary = versions.resolve(".publishing-" + UUID.randomUUID()).normalize();
        Path destination = versions.resolve(String.valueOf(version)).normalize();
        requireWithin(template, temporary);
        requireWithin(template, destination);
        try {
            Files.createDirectories(temporary);
            Map<String, Object> manifest = deepMap(draft.getManifest());
            manifest.put("version", Long.valueOf(version));
            manifest.put("status", "published");
            manifest.put("published-at", Instant.now().toString());
            writeAtomic(temporary.resolve("manifest.yml"), yaml.dump(manifest).getBytes(StandardCharsets.UTF_8));
            writeAtomic(temporary.resolve("scene.yml"),
                    yaml.dump(draft.getScene()).getBytes(StandardCharsets.UTF_8));
            copyAssets(template.resolve("assets"), temporary.resolve("assets"));
            validateSnapshot(temporary, normalized, version);
            moveNew(temporary, destination);
            writePointer(template, version, digestTree(destination));
            snapshotCache.clear();
            refresh();
            return version;
        } finally {
            deleteTreeQuietly(temporary);
        }
    }

    synchronized void rollback(String id, long version) throws IOException {
        String normalized = templateId(id);
        Path template = templateDirectory(normalized);
        Path destination = template.resolve("versions").resolve(String.valueOf(version)).normalize();
        requireWithin(template, destination);
        requireDirectory(destination);
        validateSnapshot(destination, normalized, version);
        writePointer(template, version, digestTree(destination));
        snapshotCache.clear();
        refresh();
    }

    synchronized TemplateSnapshot loadDraft(String id) throws IOException {
        return validateSnapshot(templateDirectory(id), templateId(id), 1L);
    }

    synchronized Map<String, Object> readDraftBundle(String id) throws IOException {
        Path template = templateDirectory(id);
        Map<String, Object> bundle = new LinkedHashMap<String, Object>();
        bundle.put("manifest", yaml.loadMap(template.resolve("manifest.yml")));
        bundle.put("scene", yaml.loadMap(template.resolve("scene.yml")));
        bundle.put("versions", listVersions(id));
        return bundle;
    }

    synchronized Map<String, String> readDraftSources(String id) throws IOException {
        Path template = templateDirectory(id);
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put("manifest", readText(template.resolve("manifest.yml")));
        source.put("scene", readText(template.resolve("scene.yml")));
        return source;
    }

    synchronized void saveDraft(String id,
                                Map<String, Object> manifest,
                                Map<String, Object> scene) throws IOException {
        String normalized = templateId(id);
        Path template = templateDirectory(normalized);
        Files.createDirectories(template.resolve("assets"));
        Path manifestTemp = template.resolve(".manifest-" + UUID.randomUUID() + ".tmp");
        Path sceneTemp = template.resolve(".scene-" + UUID.randomUUID() + ".tmp");
        try {
            Files.write(manifestTemp, yaml.dump(manifest).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.write(sceneTemp, yaml.dump(scene).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            validateSnapshotFiles(template, manifestTemp, sceneTemp, normalized, 1L);
            moveReplacing(manifestTemp, template.resolve("manifest.yml"));
            moveReplacing(sceneTemp, template.resolve("scene.yml"));
        } finally {
            Files.deleteIfExists(manifestTemp);
            Files.deleteIfExists(sceneTemp);
        }
    }

    synchronized void saveDraftSources(String id, String manifest, String scene) throws IOException {
        saveDraft(id, yaml.loadMap(manifest), yaml.loadMap(scene));
    }

    synchronized void uploadAsset(String id, String name, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > settings.getMaximumAssetBytes()) {
            throw new IOException("Uploaded asset has an invalid size");
        }
        Path template = templateDirectory(id);
        Path assets = template.resolve("assets").normalize();
        Files.createDirectories(assets);
        Path destination = safeAssetPath(assets, name);
        Files.createDirectories(destination.getParent());
        for (Path current = destination.getParent(); current != null && current.startsWith(assets);
             current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Asset path contains a symbolic link");
        }
        if (Files.isSymbolicLink(destination)) {
            throw new IOException("Asset path contains a symbolic link");
        }
        long previousBytes = Files.isRegularFile(destination) ? Files.size(destination) : 0L;
        if (treeBytes(assets) - previousBytes + bytes.length
                > settings.getMaximumTemplateAssetBytes()) {
            throw new IOException("Template assets exceed the configured total size limit");
        }
        Path temporary = destination.resolveSibling("." + destination.getFileName() + ".tmp");
        Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            moveReplacing(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized List<Long> listVersions(String id) throws IOException {
        Path versions = templateDirectory(id).resolve("versions");
        if (!Files.isDirectory(versions) || Files.isSymbolicLink(versions)) return Collections.emptyList();
        List<Long> result = new ArrayList<Long>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(versions)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry) || Files.isSymbolicLink(entry)) continue;
                try {
                    long version = Long.parseLong(entry.getFileName().toString());
                    if (version > 0L) result.add(Long.valueOf(version));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Collections.sort(result, Collections.reverseOrder());
        return result;
    }

    private void refresh() throws IOException {
        List<ImageTemplateInfo> infos = new ArrayList<ImageTemplateInfo>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry) || Files.isSymbolicLink(entry)) continue;
                String id = entry.getFileName().toString();
                try {
                    infos.add(loadPublished(id).getInfo());
                } catch (IOException exception) {
                    host.warn("Ignoring unavailable image template " + id + ": " + exception.getMessage());
                }
            }
        }
        Collections.sort(infos, new Comparator<ImageTemplateInfo>() {
            @Override
            public int compare(ImageTemplateInfo left, ImageTemplateInfo right) {
                return left.getId().compareTo(right.getId());
            }
        });
        publishedTemplates = Collections.unmodifiableList(infos);
    }

    private TemplateSnapshot validateSnapshot(Path directory,
                                              String expectedId,
                                              long expectedVersion) throws IOException {
        return validateSnapshotFiles(directory, directory.resolve("manifest.yml"),
                directory.resolve("scene.yml"), expectedId, expectedVersion);
    }

    @SuppressWarnings("unchecked")
    private TemplateSnapshot validateSnapshotFiles(Path directory,
                                                   Path manifestFile,
                                                   Path sceneFile,
                                                   String expectedId,
                                                   long expectedVersion) throws IOException {
        requireDirectory(directory);
        Map<String, Object> manifest = yaml.loadMap(manifestFile);
        Map<String, Object> scene = yaml.loadMap(sceneFile);
        if (!"template-v1".equals(string(manifest.get("schema-version")))) {
            throw new IOException("manifest.yml must use schema-version: template-v1");
        }
        String id = templateId(string(manifest.get("id")));
        if (!id.equals(expectedId)) throw new IOException("Template ID does not match its directory name");
        if (!"scene-v1".equals(string(scene.get("schema-version")))) {
            throw new IOException("scene.yml must use schema-version: scene-v1");
        }
        Object canvasValue = scene.get("canvas");
        if (!(canvasValue instanceof Map<?, ?>)) throw new IOException("scene.yml canvas must be a mapping");
        Map<String, Object> canvas = (Map<String, Object>) canvasValue;
        int width = integer(canvas.get("width"), 0);
        int height = integer(canvas.get("height"), 0);
        if (width < 1 || width > settings.getMaximumWidth()
                || height < 1 || height > settings.getMaximumHeight()
                || (long) width * height > settings.getMaximumPixels()) {
            throw new IOException("Template canvas exceeds configured limits");
        }
        Object layersValue = scene.get("layers");
        if (!(layersValue instanceof List<?>)) throw new IOException("scene.yml layers must be a list");
        int layers = validateNodes((List<?>) layersValue, 0);
        if (layers > settings.getMaximumLayers()) {
            throw new IOException("Template contains too many layers");
        }
        List<ImageDataProviderSpec> providers = providers(manifest.get("providers"));
        long version = longValue(manifest.get("version"), expectedVersion);
        if (!directory.equals(templateDirectory(expectedId)) && version != expectedVersion) {
            throw new IOException("Published template version does not match its directory");
        }
        String name = string(manifest.get("name"));
        String fileName = safeFileName(string(manifest.get("suggested-file-name")), id + ".png");
        ImageTemplateInfo info = new ImageTemplateInfo(id, name, version, width, height, providers);
        validateAssets(directory.resolve("assets"));
        return new TemplateSnapshot(info, deepMap(manifest), deepMap(scene), directory, fileName);
    }

    @SuppressWarnings("unchecked")
    private int validateNodes(List<?> nodes, int depth) throws IOException {
        if (depth > MAX_TREE_DEPTH) throw new IOException("Template layer nesting is too deep");
        int count = 0;
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?>)) throw new IOException("Every template layer must be a mapping");
            Map<String, Object> node = (Map<String, Object>) value;
            String type = string(node.get("type")).toLowerCase(Locale.ROOT);
            if (!NODE_TYPES.contains(type)) throw new IOException("Unsupported template layer type: " + type);
            count++;
            Object children = node.get("children");
            if (children != null) {
                if (!(children instanceof List<?>)) throw new IOException(type + " children must be a list");
                count += validateNodes((List<?>) children, depth + 1);
            }
            Object thenNodes = node.get("then");
            Object elseNodes = node.get("else");
            if (thenNodes != null) {
                if (!(thenNodes instanceof List<?>)) throw new IOException("condition then must be a list");
                count += validateNodes((List<?>) thenNodes, depth + 1);
            }
            if (elseNodes != null) {
                if (!(elseNodes instanceof List<?>)) throw new IOException("condition else must be a list");
                count += validateNodes((List<?>) elseNodes, depth + 1);
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private List<ImageDataProviderSpec> providers(Object value) throws IOException {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List<?>)) throw new IOException("manifest providers must be a list");
        List<?> declarations = (List<?>) value;
        if (declarations.size() > MAX_PROVIDER_DECLARATIONS) {
            throw new IOException("Template declares too many data providers");
        }
        List<ImageDataProviderSpec> result = new ArrayList<ImageDataProviderSpec>();
        Set<String> ids = new HashSet<String>();
        for (Object declaration : declarations) {
            String id;
            Map<String, Object> options = Collections.emptyMap();
            if (declaration instanceof Map<?, ?>) {
                Map<String, Object> map = (Map<String, Object>) declaration;
                id = string(map.get("id"));
                options = new LinkedHashMap<String, Object>(map);
                options.remove("id");
            } else {
                id = string(declaration);
            }
            try {
                ImageDataProviderSpec spec = new ImageDataProviderSpec(id, options);
                if (!ids.add(spec.getId())) throw new IOException("Duplicate data provider: " + spec.getId());
                result.add(spec);
            } catch (IllegalArgumentException exception) {
                throw new IOException(exception.getMessage(), exception);
            }
        }
        return result;
    }

    private void installExample() throws IOException {
        Path template = templateDirectory("online-status");
        Files.createDirectories(template);
        requireDirectory(template);
        Path assets = template.resolve("assets");
        if (Files.isSymbolicLink(assets)) throw new IOException("Template assets cannot be a symbolic link");
        Files.createDirectories(assets);
        requireDirectory(assets);
        completeResource("/defaults/online-status/manifest.yml", template.resolve("manifest.yml"));
        completeResource("/defaults/online-status/scene.yml", template.resolve("scene.yml"));
    }

    private void completeResource(String name, Path destination) throws IOException {
        if (Files.isSymbolicLink(destination)) throw new IOException("Template file cannot be a symbolic link");
        String source = resourceText(name);
        if (!Files.exists(destination)) {
            writeAtomic(destination, source.getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> current = yaml.loadMap(destination, true);
        if (mergeMissing(current, yaml.loadMap(source))) {
            writeAtomic(destination, yaml.dump(current).getBytes(StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean mergeMissing(Map<String, Object> current, Map<String, Object> defaults) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            Object value = current.get(entry.getKey());
            if (value == null && (!current.containsKey(entry.getKey()) || entry.getValue() != null)) {
                current.put(entry.getKey(), entry.getValue());
                changed = true;
            } else if (value instanceof Map<?, ?> && entry.getValue() instanceof Map<?, ?>) {
                changed |= mergeMissing((Map<String, Object>) value, (Map<String, Object>) entry.getValue());
            }
            // A customized layer/provider list is one value, not a list to merge by index.
        }
        return changed;
    }

    private String resourceText(String name) throws IOException {
        try (InputStream input = TemplateRepository.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Renderer is missing " + name);
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
                if (output.size() > 512 * 1024) throw new IOException("Bundled template is too large");
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void writePointer(Path template, long version, String digest) throws IOException {
        Map<String, Object> pointer = new LinkedHashMap<String, Object>();
        pointer.put("version", Long.valueOf(version));
        pointer.put("sha256", digest);
        writeAtomic(template.resolve("published.yml"),
                yaml.dump(pointer).getBytes(StandardCharsets.UTF_8));
    }

    private Path templateDirectory(String id) throws IOException {
        Path directory = root.resolve(templateId(id)).normalize();
        requireWithin(root, directory);
        if (Files.exists(directory) && Files.isSymbolicLink(directory)) {
            throw new IOException("Template directory cannot be a symbolic link");
        }
        return directory;
    }

    private Path safeAssetPath(Path assets, String name) throws IOException {
        String normalized = name == null ? "" : name.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("../")
                || normalized.equals("..") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("Invalid asset path");
        }
        Path path = assets.resolve(normalized).normalize();
        requireWithin(assets, path);
        return path;
    }

    private String templateId(String value) throws IOException {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IOException("Invalid image template ID: " + value);
        }
        return id;
    }

    private void validateAssets(Path assets) throws IOException {
        if (!Files.exists(assets)) return;
        requireDirectory(assets);
        final long[] total = new long[1];
        Files.walkFileTree(assets, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("Template assets cannot contain symbolic links");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new IOException("Template assets must be regular files");
                }
                if (attrs.size() > settings.getMaximumAssetBytes()) {
                    throw new IOException("Template asset exceeds the per-file size limit: " + file.getFileName());
                }
                total[0] += attrs.size();
                if (total[0] > settings.getMaximumTemplateAssetBytes()) {
                    throw new IOException("Template assets exceed the total size limit");
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyAssets(Path source, Path destination) throws IOException {
        if (!Files.exists(source)) {
            Files.createDirectories(destination);
            return;
        }
        validateAssets(source);
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = destination.resolve(source.relativize(dir).toString()).normalize();
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = destination.resolve(source.relativize(file).toString()).normalize();
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private long nextVersion(Path versions) throws IOException {
        long maximum = 0L;
        if (Files.isDirectory(versions)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(versions)) {
                for (Path entry : entries) {
                    try {
                        maximum = Math.max(maximum, Long.parseLong(entry.getFileName().toString()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (maximum == Long.MAX_VALUE) throw new IOException("Template version limit reached");
        return maximum + 1L;
    }

    private String digestTree(Path directory) throws IOException {
        final List<Path> files = new ArrayList<Path>();
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new IOException("Published template contains an invalid file");
                }
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return directory.relativize(left).toString().compareTo(directory.relativize(right).toString());
            }
        });
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
        for (Path file : files) {
            digest.update(directory.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private long treeBytes(Path directory) throws IOException {
        if (!Files.exists(directory)) return 0L;
        final long[] total = new long[1];
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private String readText(Path file) throws IOException {
        if (Files.size(file) > 512L * 1024L) throw new IOException("Template source is too large");
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void moveNew(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void requireDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            throw new IOException("Required directory is unavailable or unsafe: " + directory);
        }
    }

    private void requireWithin(Path parent, Path child) throws IOException {
        if (!child.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize())) {
            throw new IOException("Template path escapes its allowed directory");
        }
    }

    private void deleteTreeQuietly(Path directory) {
        if (directory == null || !directory.startsWith(root) || !Files.exists(directory)) return;
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object deepValue(Object value) {
        if (value instanceof Map<?, ?>) return deepMap((Map<String, Object>) value);
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (List<?>) value) copy.add(deepValue(item));
            return copy;
        }
        return value;
    }

    private String safeFileName(String value, String fallback) {
        String name = value == null || value.trim().isEmpty() ? fallback : value.trim();
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash < 0 ? name : name.substring(slash + 1);
        return name.isEmpty() || name.equals(".") || name.equals("..") ? fallback : name;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(string(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(string(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
