package haaa.shitbot.core.inventory;

import com.google.gson.JsonElement;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.JsonUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ItemIconResolver {
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 32768;
    private static final long MAX_IMAGE_PIXELS = 64L * 1024L * 1024L;
    private static final int MAX_MODEL_DEPTH = 24;
    private static final int MAX_RESOURCE_SOURCES = 4096;
    private static final int MAX_INDEX_RESOURCES = 250000;
    private static final int ICON_SIZE = 64;
    private static final long NEGATIVE_CACHE_MILLIS = 30_000L;
    private static final int MAX_PROFILE_TEXTURE_BYTES = 1024 * 1024;
    private static final int PROFILE_CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int PROFILE_READ_TIMEOUT_MILLIS = 5000;
    private static final int MAX_PROFILE_CACHE_FILES = 4096;
    private static final long MAX_PROFILE_CACHE_BYTES = 64L * 1024L * 1024L;

    private final Settings.Inventory settings;
    private final PlatformBridge platform;
    private final Path dataDirectory;
    private final Path exportedDirectory;
    private final Path profileCacheDirectory;
    private final AtomicReference<CompletableFuture<ResourceIndex>> indexFuture =
            new AtomicReference<CompletableFuture<ResourceIndex>>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean sourceCheckRunning = new AtomicBoolean();
    private final AtomicBoolean profileCacheCleanupRunning = new AtomicBoolean();
    private final AtomicLong profileCacheWrites = new AtomicLong();
    private final Object[] profileLocks = createLocks(64);
    private final Map<String, CacheValue> cache;
    private final Map<String, Boolean> warningCache;
    private volatile Executor indexExecutor;
    private volatile long lastSourceCheck;
    private volatile long lastSourceFingerprint = Long.MIN_VALUE;

    public ItemIconResolver(Settings.Inventory settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
        this.dataDirectory = platform.getDataDirectory().toAbsolutePath().normalize();
        this.exportedDirectory = resolve(settings.getExportedIconsDirectory());
        this.profileCacheDirectory = dataDirectory.resolve("inventory-head-cache").normalize();
        final int maximumEntries = settings.getIconCacheEntries();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheValue>(64, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheValue> eldest) {
                return size() > maximumEntries;
            }
        });
        this.warningCache = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(64, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 256;
            }
        });
    }

    /** Starts or refreshes resource indexing without blocking a platform thread. */
    public CompletableFuture<Void> prepareAsync(Executor executor) {
        this.indexExecutor = executor;
        startProfileCacheCleanup(executor);
        return getIndexFuture(executor).thenApply(new java.util.function.Function<ResourceIndex, Void>() {
            @Override
            public Void apply(ResourceIndex ignored) {
                return null;
            }
        });
    }

    /** Kept for source compatibility with the first inventory patch. */
    public CompletableFuture<Void> warmUpAsync(Executor executor) {
        return prepareAsync(executor);
    }

    /**
     * Returns a token that changes whenever the indexed resource set changes.
     * Render caches include this value so a picture produced while the index was
     * still loading cannot keep serving missing-texture placeholders afterwards.
     */
    public long getCacheGeneration() {
        refreshIfNeeded();
        CompletableFuture<ResourceIndex> future = indexFuture.get();
        if (future == null || !future.isDone() || future.isCompletedExceptionally()) return 0L;
        return future.getNow(ResourceIndex.empty()).generation;
    }

    public BufferedImage resolve(InventorySnapshot.Item item) {
        if (item == null) return null;
        refreshIfNeeded();

        BufferedImage exported = loadExported(item);
        if (exported != null) return exported;

        CompletableFuture<ResourceIndex> future = indexFuture.get();
        boolean indexReady = future != null && future.isDone() && !future.isCompletedExceptionally();
        ResourceIndex index = indexReady ? future.getNow(ResourceIndex.empty()) : ResourceIndex.empty();
        long currentGeneration = indexReady ? index.generation : 0L;
        String cacheKey = item.iconCacheKey();
        CacheValue cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.generation == currentGeneration && cached.expiresAt >= now) {
            return cached.image;
        }

        BufferedImage image = null;
        boolean profileRequested = !item.getProfileTextureHash().isEmpty();
        boolean profileResolved = false;
        try {
            if (profileRequested) {
                image = loadProfileTexture(item.getProfileTextureHash());
                profileResolved = image != null;
            }
            if (image == null && indexReady) {
                image = loadFromResources(item, index);
            }
        } catch (Throwable throwable) {
            warnOnce("resolve:" + cacheKey, "Failed to resolve item icon "
                    + item.getRegistryId() + ": " + safeMessage(throwable));
        }

        // Do not negative-cache while the resource index is still loading. A custom
        // profile head can be resolved independently, but ordinary items must be
        // retried as soon as indexing completes.
        if (image == null && !indexReady) return null;
        long expiresAt = image == null || (profileRequested && !profileResolved)
                ? now + NEGATIVE_CACHE_MILLIS : Long.MAX_VALUE;
        cache.put(cacheKey, new CacheValue(image, currentGeneration, expiresAt));
        return image;
    }

    private BufferedImage loadProfileTexture(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{32,128}")) return null;
        Path cacheFile = profileCachePath(hash);
        BufferedImage cached = readProfileCacheImage(cacheFile);
        if (cached != null) return cached;

        Object lock = profileLocks[(hash.hashCode() & Integer.MAX_VALUE) % profileLocks.length];
        synchronized (lock) {
            cached = readProfileCacheImage(cacheFile);
            if (cached != null) return cached;
            BufferedImage skin = downloadProfileSkin(hash);
            if (skin == null) return null;
            BufferedImage icon = renderPlayerHead(skin);
            if (icon == null) return null;
            writeProfileCache(cacheFile, icon);
            return icon;
        }
    }

    private BufferedImage downloadProfileSkin(String hash) {
        HttpURLConnection connection = null;
        try {
            // The host and path are fixed; only a validated hexadecimal content hash
            // is appended, so NBT/profile data cannot turn this into an SSRF request.
            URL url = new URL("https://textures.minecraft.net/texture/" + hash);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(PROFILE_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(PROFILE_READ_TIMEOUT_MILLIS);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "ShitBot-Inventory/1");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                warnOnce("profile-http:" + hash, "Player-head texture request returned HTTP " + status);
                return null;
            }
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_PROFILE_TEXTURE_BYTES) {
                throw new IOException("Player-head texture exceeds safety limit");
            }
            try (InputStream input = connection.getInputStream()) {
                return decodeImage(readBounded(input, MAX_PROFILE_TEXTURE_BYTES));
            }
        } catch (Throwable throwable) {
            warnOnce("profile:" + hash, "Failed to load player-head texture: " + safeMessage(throwable));
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private BufferedImage renderPlayerHead(BufferedImage skin) {
        if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 32) return null;
        int scale = skin.getWidth() / 64;
        if (scale <= 0 || skin.getWidth() != 64 * scale || skin.getHeight() < 32 * scale) return null;

        BufferedImage front = skinFace(skin, scale, 8, 8, 40, 8);
        BufferedImage top = skinFace(skin, scale, 8, 0, 40, 0);
        BufferedImage side = skinFace(skin, scale, 0, 8, 32, 8);
        if (front == null || top == null || side == null) return null;
        return renderCube(top, side, front);
    }

    private BufferedImage skinFace(BufferedImage skin,
                                   int scale,
                                   int baseX,
                                   int baseY,
                                   int overlayX,
                                   int overlayY) {
        int size = 8 * scale;
        if (!containsRegion(skin, baseX * scale, baseY * scale, size, size)) return null;
        BufferedImage output = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(skin,
                    0, 0, size, size,
                    baseX * scale, baseY * scale,
                    (baseX + 8) * scale, (baseY + 8) * scale,
                    null);
            if (containsRegion(skin, overlayX * scale, overlayY * scale, size, size)) {
                graphics.drawImage(skin,
                        0, 0, size, size,
                        overlayX * scale, overlayY * scale,
                        (overlayX + 8) * scale, (overlayY + 8) * scale,
                        null);
            }
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private boolean containsRegion(BufferedImage image, int x, int y, int width, int height) {
        return x >= 0 && y >= 0 && width > 0 && height > 0
                && x + width <= image.getWidth() && y + height <= image.getHeight();
    }

    private Path profileCachePath(String hash) {
        return profileCacheDirectory.resolve(hash.substring(0, 2)).resolve(hash + ".png").normalize();
    }

    private BufferedImage readProfileCacheImage(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(profileCacheDirectory) || !Files.isRegularFile(normalized)
                    || Files.size(normalized) > MAX_PROFILE_TEXTURE_BYTES) return null;
            BufferedImage image;
            try (InputStream input = Files.newInputStream(normalized)) {
                image = decodeImage(readBounded(input, MAX_PROFILE_TEXTURE_BYTES));
            }
            if (image == null) {
                Files.deleteIfExists(normalized);
                return null;
            }
            try {
                Files.setLastModifiedTime(normalized, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException ignored) {
                // Cache access-time refresh is best effort.
            }
            return normalizeIcon(image);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeProfileCache(Path path, BufferedImage image) {
        Path temporary = null;
        boolean stored = false;
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(profileCacheDirectory)) return;
            Files.createDirectories(normalized.getParent());
            temporary = normalized.resolveSibling(normalized.getFileName().toString()
                    + ".tmp-" + Thread.currentThread().getId());
            if (!ImageIO.write(image, "png", temporary.toFile())) return;
            try {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            stored = true;
        } catch (Throwable throwable) {
            warnOnce("profile-cache-write", "Failed to write player-head cache: " + safeMessage(throwable));
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { }
            }
        }
        if (stored && profileCacheWrites.incrementAndGet() % 128L == 0L) {
            startProfileCacheCleanup(indexExecutor);
        }
    }

    private void startProfileCacheCleanup(Executor executor) {
        if (executor == null || !profileCacheCleanupRunning.compareAndSet(false, true)) return;
        try {
            CompletableFuture.runAsync(new Runnable() {
                @Override
                public void run() {
                    try {
                        cleanupProfileCache();
                    } finally {
                        profileCacheCleanupRunning.set(false);
                    }
                }
            }, executor);
        } catch (Throwable throwable) {
            profileCacheCleanupRunning.set(false);
        }
    }

    private void cleanupProfileCache() {
        if (!Files.isDirectory(profileCacheDirectory)) return;
        final List<Path> files = new ArrayList<Path>();
        final long[] totalBytes = {0L};
        try {
            Files.walkFileTree(profileCacheDirectory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.getFileName() == null ? "" : file.getFileName().toString();
                    if (name.endsWith(".png")) {
                        files.add(file);
                        totalBytes[0] += Math.max(0L, attributes.size());
                    } else if (name.contains(".tmp-")) {
                        try { Files.deleteIfExists(file); }
                        catch (IOException ignored) { }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            Collections.sort(files, new Comparator<Path>() {
                @Override
                public int compare(Path left, Path right) {
                    long difference = safeLastModified(left) - safeLastModified(right);
                    return difference < 0L ? -1 : difference > 0L ? 1 : 0;
                }
            });
            int remaining = files.size();
            for (Path file : files) {
                if (remaining <= MAX_PROFILE_CACHE_FILES && totalBytes[0] <= MAX_PROFILE_CACHE_BYTES) break;
                long size = safeSize(file);
                try {
                    if (Files.deleteIfExists(file)) {
                        remaining--;
                        totalBytes[0] = Math.max(0L, totalBytes[0] - size);
                    }
                } catch (IOException ignored) {
                    // Continue cleaning other entries.
                }
            }
        } catch (Throwable throwable) {
            warnOnce("profile-cache-cleanup", "Failed to clean player-head cache: "
                    + safeMessage(throwable));
        }
    }

    private byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, maximumBytes));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) throw new IOException("Input exceeds safety limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private BufferedImage loadExported(InventorySnapshot.Item item) {
        Identifier id = Identifier.parse(item.getRegistryId(), "minecraft");
        if (id == null) return null;
        Path namespaceDirectory = exportedDirectory.resolve(id.namespace).normalize();
        if (!namespaceDirectory.startsWith(exportedDirectory)) return null;

        List<String> suffixes = new ArrayList<String>();
        if (item.getCustomModelData() != null) {
            suffixes.add("__cmd_" + item.getCustomModelData().intValue());
        }
        if (item.getLegacyData() != 0) {
            suffixes.add("__data_" + item.getLegacyData());
        }
        suffixes.add("");
        for (String suffix : suffixes) {
            BufferedImage exact = readImage(namespaceDirectory.resolve(id.path + suffix + ".png"));
            if (exact != null) return normalizeIcon(exact);
        }
        return null;
    }

    private BufferedImage loadFromResources(InventorySnapshot.Item item, ResourceIndex index) throws IOException {
        Identifier itemId = Identifier.parse(item.getRegistryId(), "minecraft");
        if (itemId == null) return null;

        List<Identifier> modelCandidates = new ArrayList<Identifier>();
        Identifier definitionId = item.getItemModel().isEmpty()
                ? itemId : Identifier.parse(item.getItemModel(), itemId.namespace);
        try {
            addUnique(modelCandidates, resolveItemDefinition(definitionId, item, index));
        } catch (Throwable throwable) {
            warnOnce("definition:" + definitionId, "Skipping broken item definition "
                    + definitionId + ": " + safeMessage(throwable));
        }
        addUnique(modelCandidates, new Identifier(itemId.namespace, "item/" + itemId.path));
        for (String path : LegacyItemMappings.modelPaths(item)) {
            addUnique(modelCandidates, new Identifier(itemId.namespace, path));
            if (!"minecraft".equals(itemId.namespace)) {
                addUnique(modelCandidates, new Identifier("minecraft", path));
            }
        }

        // One malformed high-priority resource must not prevent lower-level aliases
        // or direct 1.7/1.8 textures from being tried.
        for (Identifier candidate : modelCandidates) {
            try {
                Model model = loadModel(candidate, item, index, 0, new HashSet<Identifier>());
                if (model == null) continue;
                BufferedImage rendered = renderModel(model, candidate.namespace, item, index);
                if (rendered != null) return rendered;
            } catch (Throwable throwable) {
                warnOnce("model:" + candidate, "Skipping broken inventory model "
                        + candidate + ": " + safeMessage(throwable));
            }
        }

        BufferedImage direct = loadDirectTexture(itemId.namespace, item, index);
        if (direct == null && !"minecraft".equals(itemId.namespace)) {
            direct = loadDirectTexture("minecraft", item, index);
        }
        if (direct != null) return direct;
        return LegacyItemMappings.isPlayerHead(item) ? loadDefaultPlayerHead(index) : null;
    }

    private BufferedImage loadDefaultPlayerHead(ResourceIndex index) {
        for (String path : new String[]{
                "entity/steve", "entity/player/wide/steve",
                "entity/alex", "entity/player/slim/alex"}) {
            try {
                Resource resource = findTextureResource(new Identifier("minecraft", path), index);
                if (resource == null) continue;
                BufferedImage icon = renderPlayerHead(readRawTexture(resource));
                if (icon != null) return icon;
            } catch (Throwable throwable) {
                warnOnce("default-head:" + path, "Skipping broken default player skin "
                        + path + ": " + safeMessage(throwable));
            }
        }
        return null;
    }

    private BufferedImage renderModel(Model model,
                                      String defaultNamespace,
                                      InventorySnapshot.Item item,
                                      ResourceIndex index) throws IOException {
        BufferedImage flat = renderLayers(model, defaultNamespace, index);
        if (flat != null && (model.flat || !model.hasElements)) return flat;

        BufferedImage block = renderBlockApproximation(model, defaultNamespace, index);
        if (block != null) return block;
        if (flat != null) return flat;

        String representative = representativeTexture(model);
        BufferedImage texture = loadTexture(representative, model.textures, defaultNamespace, index);
        if (texture == null) return null;
        return LegacyItemMappings.likelyBlock(item)
                ? renderCube(texture, texture, texture) : normalizeIcon(texture);
    }

    private BufferedImage renderLayers(Model model, String defaultNamespace, ResourceIndex index) throws IOException {
        List<BufferedImage> layers = new ArrayList<BufferedImage>();
        for (int i = 0; i < 16; i++) {
            BufferedImage layer = loadTexture(model.textures.get("layer" + i),
                    model.textures, defaultNamespace, index);
            if (layer != null) layers.add(layer);
        }
        if (layers.isEmpty()) return null;
        BufferedImage result = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (BufferedImage layer : layers) {
                BufferedImage frame = firstAnimationFrame(layer);
                graphics.drawImage(frame, 0, 0, ICON_SIZE, ICON_SIZE, null);
            }
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private BufferedImage renderBlockApproximation(Model model,
                                                   String defaultNamespace,
                                                   ResourceIndex index) throws IOException {
        String topReference = firstNonEmpty(
                model.faceTextures.get("up"), model.textures.get("top"), model.textures.get("end"),
                model.textures.get("all"), model.textures.get("particle"));
        String northReference = firstNonEmpty(
                model.faceTextures.get("north"), model.faceTextures.get("west"),
                model.textures.get("side"), model.textures.get("north"), model.textures.get("all"),
                model.textures.get("particle"));
        String eastReference = firstNonEmpty(
                model.faceTextures.get("east"), model.faceTextures.get("south"),
                model.textures.get("side"), model.textures.get("east"), model.textures.get("all"),
                model.textures.get("particle"));

        BufferedImage top = loadTexture(topReference, model.textures, defaultNamespace, index);
        BufferedImage left = loadTexture(northReference, model.textures, defaultNamespace, index);
        BufferedImage right = loadTexture(eastReference, model.textures, defaultNamespace, index);
        BufferedImage fallback = firstNonNull(top, left, right,
                loadTexture(representativeTexture(model), model.textures, defaultNamespace, index));
        if (fallback == null) return null;
        return renderCube(top == null ? fallback : top,
                left == null ? fallback : left,
                right == null ? fallback : right);
    }

    private BufferedImage renderCube(BufferedImage top, BufferedImage left, BufferedImage right) {
        BufferedImage output = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage topFrame = firstAnimationFrame(top);
        BufferedImage leftFrame = firstAnimationFrame(left);
        BufferedImage rightFrame = firstAnimationFrame(right);
        drawTexturedQuad(output, topFrame,
                new Point(32, 4), new Point(60, 18), new Point(32, 32), new Point(4, 18), 1.0F);
        drawTexturedQuad(output, leftFrame,
                new Point(4, 18), new Point(32, 32), new Point(32, 60), new Point(4, 46), 0.78F);
        drawTexturedQuad(output, rightFrame,
                new Point(32, 32), new Point(60, 18), new Point(60, 46), new Point(32, 60), 0.63F);
        return output;
    }

    private void drawTexturedQuad(BufferedImage target,
                                  BufferedImage texture,
                                  Point p0, Point p1, Point p2, Point p3,
                                  float brightness) {
        rasterTriangle(target, texture, p0, p1, p2,
                new Tex(0, 0), new Tex(1, 0), new Tex(1, 1), brightness);
        rasterTriangle(target, texture, p0, p2, p3,
                new Tex(0, 0), new Tex(1, 1), new Tex(0, 1), brightness);
    }

    private void rasterTriangle(BufferedImage target, BufferedImage texture,
                                Point a, Point b, Point c,
                                Tex ta, Tex tb, Tex tc, float brightness) {
        int minX = Math.max(0, Math.min(a.x, Math.min(b.x, c.x)));
        int maxX = Math.min(target.getWidth() - 1, Math.max(a.x, Math.max(b.x, c.x)));
        int minY = Math.max(0, Math.min(a.y, Math.min(b.y, c.y)));
        int maxY = Math.min(target.getHeight() - 1, Math.max(a.y, Math.max(b.y, c.y)));
        double area = edge(a.x, a.y, b.x, b.y, c.x, c.y);
        if (Math.abs(area) < 0.0001D) return;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5D;
                double py = y + 0.5D;
                double wa = edge(b.x, b.y, c.x, c.y, px, py) / area;
                double wb = edge(c.x, c.y, a.x, a.y, px, py) / area;
                double wc = 1.0D - wa - wb;
                if (wa < -0.001D || wb < -0.001D || wc < -0.001D) continue;
                double u = wa * ta.u + wb * tb.u + wc * tc.u;
                double v = wa * ta.v + wb * tb.v + wc * tc.v;
                int tx = clamp((int) Math.floor(u * texture.getWidth()), 0, texture.getWidth() - 1);
                int ty = clamp((int) Math.floor(v * texture.getHeight()), 0, texture.getHeight() - 1);
                int argb = texture.getRGB(tx, ty);
                int alpha = (argb >>> 24) & 255;
                if (alpha == 0) continue;
                int red = clamp(Math.round(((argb >>> 16) & 255) * brightness), 0, 255);
                int green = clamp(Math.round(((argb >>> 8) & 255) * brightness), 0, 255);
                int blue = clamp(Math.round((argb & 255) * brightness), 0, 255);
                target.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private BufferedImage loadDirectTexture(String namespace,
                                            InventorySnapshot.Item item,
                                            ResourceIndex index) {
        for (String texturePath : LegacyItemMappings.texturePaths(item)) {
            try {
                Resource resource = findTextureResource(new Identifier(namespace, texturePath), index);
                if (resource == null) continue;
                BufferedImage texture = readTexture(resource);
                if (texture == null) continue;
                return LegacyItemMappings.likelyBlock(item)
                        ? renderCube(texture, texture, texture) : normalizeIcon(texture);
            } catch (Throwable throwable) {
                warnOnce("texture:" + namespace + ':' + texturePath,
                        "Skipping broken inventory texture " + namespace + ':' + texturePath
                                + ": " + safeMessage(throwable));
            }
        }
        return null;
    }

    private Identifier resolveItemDefinition(Identifier definitionId,
                                             InventorySnapshot.Item item,
                                             ResourceIndex index) throws IOException {
        if (definitionId == null) return null;
        Resource resource = index.resources.get("assets/" + definitionId.namespace
                + "/items/" + definitionId.path + ".json");
        if (resource == null) return null;
        JsonElement root = JsonUtil.parse(resource.read(MAX_JSON_BYTES));
        JsonElement modelNode = JsonUtil.get(root, "model");
        return evaluateItemModel(modelNode, definitionId.namespace, item, 0);
    }

    private Identifier evaluateItemModel(JsonElement node,
                                         String defaultNamespace,
                                         InventorySnapshot.Item item,
                                         int depth) {
        if (node == null || depth > MAX_MODEL_DEPTH) return null;
        if (JsonUtil.isString(node)) return Identifier.parse(JsonUtil.string(node, ""), defaultNamespace);
        if (!node.isJsonObject()) return null;
        String type = JsonUtil.string(node, "type", "").toLowerCase(Locale.ROOT);
        String shortType = type.indexOf(':') >= 0 ? type.substring(type.indexOf(':') + 1) : type;

        if ("model".equals(shortType) || type.isEmpty()) {
            JsonElement model = JsonUtil.get(node, "model");
            if (JsonUtil.isString(model)) {
                return Identifier.parse(JsonUtil.string(model, ""), defaultNamespace);
            }
        }
        if ("special".equals(shortType)) {
            Identifier base = Identifier.parse(JsonUtil.string(node, "base", ""), defaultNamespace);
            if (base != null) return base;
            return evaluateItemModel(JsonUtil.get(node, "model"), defaultNamespace, item, depth + 1);
        }
        if ("composite".equals(shortType)) {
            JsonElement models = JsonUtil.get(node, "models");
            if (models != null && models.isJsonArray()) {
                for (JsonElement child : models.getAsJsonArray()) {
                    Identifier candidate = evaluateItemModel(child, defaultNamespace, item, depth + 1);
                    if (candidate != null) return candidate;
                }
            }
        }
        if ("condition".equals(shortType)) {
            boolean value = evaluateBooleanProperty(node, item);
            Identifier selected = evaluateItemModel(JsonUtil.get(node, value ? "on_true" : "on_false"),
                    defaultNamespace, item, depth + 1);
            if (selected != null) return selected;
        }
        if ("range_dispatch".equals(shortType)) {
            double value = evaluateNumericProperty(node, item);
            Identifier selected = null;
            double selectedThreshold = -Double.MAX_VALUE;
            JsonElement entries = JsonUtil.get(node, "entries");
            if (entries != null && entries.isJsonArray()) {
                for (JsonElement entry : entries.getAsJsonArray()) {
                    double threshold = JsonUtil.doubleValue(entry, "threshold", Double.NaN);
                    if (!Double.isNaN(threshold) && threshold <= value && threshold >= selectedThreshold) {
                        Identifier candidate = evaluateItemModel(
                                JsonUtil.get(entry, "model"), defaultNamespace, item, depth + 1);
                        if (candidate != null) {
                            selected = candidate;
                            selectedThreshold = threshold;
                        }
                    }
                }
            }
            if (selected != null) return selected;
            Identifier fallback = evaluateItemModel(
                    JsonUtil.get(node, "fallback"), defaultNamespace, item, depth + 1);
            if (fallback != null) return fallback;
        }
        if ("select".equals(shortType)) {
            String value = evaluateStringProperty(node, item);
            JsonElement cases = JsonUtil.get(node, "cases");
            if (cases != null && cases.isJsonArray()) {
                for (JsonElement caseNode : cases.getAsJsonArray()) {
                    if (matchesCase(JsonUtil.get(caseNode, "when"), value)) {
                        Identifier candidate = evaluateItemModel(
                                JsonUtil.get(caseNode, "model"), defaultNamespace, item, depth + 1);
                        if (candidate != null) return candidate;
                    }
                }
            }
            Identifier fallback = evaluateItemModel(
                    JsonUtil.get(node, "fallback"), defaultNamespace, item, depth + 1);
            if (fallback != null) return fallback;
        }

        for (String key : new String[]{"model", "base", "fallback", "on_false", "on_true"}) {
            Identifier candidate = evaluateItemModel(
                    JsonUtil.get(node, key), defaultNamespace, item, depth + 1);
            if (candidate != null) return candidate;
        }
        return findFirstModelReference(node, defaultNamespace, depth + 1);
    }

    private boolean evaluateBooleanProperty(JsonElement node, InventorySnapshot.Item item) {
        String property = JsonUtil.string(node, "property", "");
        int index = JsonUtil.integer(node, "index", 0);
        boolean value;
        if (property.endsWith("custom_model_data")) {
            value = item.getCustomModelFlag(index, false);
        } else if (property.endsWith("damaged")) {
            value = item.getDamage() > 0;
        } else if (property.endsWith("broken")) {
            value = item.hasDurability() && item.getDamage() >= item.getMaximumDurability();
        } else {
            value = false;
        }
        return JsonUtil.booleanValue(node, "invert", false) ? !value : value;
    }

    private double evaluateNumericProperty(JsonElement node, InventorySnapshot.Item item) {
        String property = JsonUtil.string(node, "property", "");
        double value;
        if (property.endsWith("custom_model_data")) {
            value = item.getCustomModelFloat(JsonUtil.integer(node, "index", 0), 0.0F);
        } else if (property.endsWith("damage")) {
            value = item.getMaximumDurability() <= 0 ? 0.0D
                    : (double) item.getDamage() / (double) item.getMaximumDurability();
        } else if (property.endsWith("count")) {
            value = item.getAmount();
        } else {
            value = 0.0D;
        }
        return value * JsonUtil.doubleValue(node, "scale", 1.0D);
    }

    private String evaluateStringProperty(JsonElement node, InventorySnapshot.Item item) {
        String property = JsonUtil.string(node, "property", "");
        if (property.endsWith("custom_model_data")) {
            return item.getCustomModelString(JsonUtil.integer(node, "index", 0));
        }
        if (property.endsWith("main_hand")) return "right";
        if (property.endsWith("display_context")) return "gui";
        return "";
    }

    private boolean matchesCase(JsonElement when, String value) {
        if (when == null) return false;
        if (JsonUtil.isString(when)) return JsonUtil.string(when, "").equals(value);
        if (when.isJsonArray()) {
            for (JsonElement candidate : when.getAsJsonArray()) {
                if (JsonUtil.isString(candidate)
                        && JsonUtil.string(candidate, "").equals(value)) return true;
            }
        }
        return false;
    }

    private Identifier findFirstModelReference(JsonElement node, String defaultNamespace, int depth) {
        if (node == null || depth > MAX_MODEL_DEPTH) return null;
        if (node.isJsonObject()) {
            JsonElement modelNode = JsonUtil.get(node, "model");
            if (JsonUtil.isString(modelNode)) {
                Identifier parsed = Identifier.parse(
                        JsonUtil.string(modelNode, ""), defaultNamespace);
                if (parsed != null) return parsed;
            }
            java.util.Iterator<Map.Entry<String, JsonElement>> fields =
                    node.getAsJsonObject().entrySet().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonElement> entry = fields.next();
                Identifier nested = findFirstModelReference(entry.getValue(), defaultNamespace, depth + 1);
                if (nested != null) return nested;
            }
        } else if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                Identifier nested = findFirstModelReference(child, defaultNamespace, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private Model loadModel(Identifier modelId,
                            InventorySnapshot.Item item,
                            ResourceIndex index,
                            int depth,
                            Set<Identifier> visiting) throws IOException {
        if (modelId == null || depth > MAX_MODEL_DEPTH || !visiting.add(modelId)) return null;
        try {
            Resource resource = index.resources.get("assets/" + modelId.namespace
                    + "/models/" + modelId.path + ".json");
            if (resource == null) return null;
            JsonElement root = JsonUtil.parse(resource.read(MAX_JSON_BYTES));
            if (root == null || !root.isJsonObject()) return null;

            Identifier selectedOverride = selectCustomModelOverride(root, item, modelId.namespace);
            if (selectedOverride != null && !selectedOverride.equals(modelId)) {
                Model selected = loadModel(selectedOverride, item, index, depth + 1, visiting);
                if (selected != null) return selected;
            }

            Model inherited = Model.empty();
            String parentText = JsonUtil.string(root, "parent", "");
            Identifier parentId = Identifier.parse(parentText, modelId.namespace);
            if (parentId != null) {
                if (isBuiltinParent(parentId)) {
                    inherited.flat = isFlatBuiltinParent(parentId);
                } else {
                    Model parent = loadModel(parentId, item, index, depth + 1, visiting);
                    if (parent != null) inherited = parent.copy();
                }
            }

            JsonElement textureNode = JsonUtil.get(root, "textures");
            if (textureNode != null && textureNode.isJsonObject()) {
                java.util.Iterator<Map.Entry<String, JsonElement>> fields =
                        textureNode.getAsJsonObject().entrySet().iterator();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonElement> entry = fields.next();
                    if (JsonUtil.isString(entry.getValue())) {
                        inherited.textures.put(
                                entry.getKey(), JsonUtil.string(entry.getValue(), ""));
                    }
                }
            }

            JsonElement elements = JsonUtil.get(root, "elements");
            if (elements != null && elements.isJsonArray()) {
                inherited.hasElements = elements.getAsJsonArray().size() > 0;
                inherited.flat = false;
                inherited.faceTextures.clear();
                for (JsonElement element : elements.getAsJsonArray()) {
                    JsonElement faces = JsonUtil.get(element, "faces");
                    if (faces == null || !faces.isJsonObject()) continue;
                    java.util.Iterator<Map.Entry<String, JsonElement>> faceFields =
                            faces.getAsJsonObject().entrySet().iterator();
                    while (faceFields.hasNext()) {
                        Map.Entry<String, JsonElement> face = faceFields.next();
                        String reference = JsonUtil.string(face.getValue(), "texture", "");
                        if (!reference.isEmpty() && !inherited.faceTextures.containsKey(face.getKey())) {
                            inherited.faceTextures.put(face.getKey(), reference);
                        }
                    }
                }
            }
            if (!inherited.hasElements && hasLayerTexture(inherited.textures)) inherited.flat = true;
            return inherited;
        } finally {
            visiting.remove(modelId);
        }
    }

    private Identifier selectCustomModelOverride(JsonElement root,
                                                 InventorySnapshot.Item item,
                                                 String defaultNamespace) {
        JsonElement overrides = JsonUtil.get(root, "overrides");
        if (overrides == null || !overrides.isJsonArray()) return null;
        Identifier selected = null;
        for (JsonElement override : overrides.getAsJsonArray()) {
            JsonElement predicate = JsonUtil.get(override, "predicate");
            if (!predicateMatches(predicate, item)) continue;
            Identifier candidate = Identifier.parse(
                    JsonUtil.string(override, "model", ""), defaultNamespace);
            if (candidate != null) selected = candidate;
        }
        return selected;
    }

    private boolean predicateMatches(JsonElement predicate, InventorySnapshot.Item item) {
        if (predicate == null || !predicate.isJsonObject()) return false;
        java.util.Iterator<Map.Entry<String, JsonElement>> fields =
                predicate.getAsJsonObject().entrySet().iterator();
        boolean sawSupported = false;
        while (fields.hasNext()) {
            Map.Entry<String, JsonElement> field = fields.next();
            String key = field.getKey();
            double threshold = JsonUtil.doubleValue(field.getValue(), Double.NaN);
            if (Double.isNaN(threshold)) return false;
            double actual;
            if (key.endsWith("custom_model_data")) {
                actual = item.getCustomModelFloat(0, 0.0F);
            } else if (key.endsWith("damage")) {
                actual = item.getMaximumDurability() <= 0 ? 0.0D
                        : (double) item.getDamage() / (double) item.getMaximumDurability();
            } else if (key.endsWith("damaged")) {
                actual = item.getDamage() > 0 ? 1.0D : 0.0D;
            } else {
                return false;
            }
            sawSupported = true;
            if (actual + 0.000001D < threshold) return false;
        }
        return sawSupported;
    }

    private boolean isBuiltinParent(Identifier id) {
        return "minecraft".equals(id.namespace)
                && ("item/generated".equals(id.path)
                || "item/handheld".equals(id.path)
                || "builtin/generated".equals(id.path)
                || "builtin/handheld".equals(id.path)
                || id.path.startsWith("builtin/"));
    }

    private boolean isFlatBuiltinParent(Identifier id) {
        return "minecraft".equals(id.namespace)
                && ("item/generated".equals(id.path)
                || "item/handheld".equals(id.path)
                || "builtin/generated".equals(id.path)
                || "builtin/handheld".equals(id.path));
    }

    private boolean hasLayerTexture(Map<String, String> textures) {
        for (String key : textures.keySet()) {
            if (key.startsWith("layer")) return true;
        }
        return false;
    }

    private String representativeTexture(Model model) {
        return firstNonEmpty(model.textures.get("layer0"), model.textures.get("particle"),
                model.textures.get("all"), model.textures.get("top"), model.textures.get("side"),
                model.faceTextures.get("up"), model.faceTextures.get("north"),
                firstValue(model.faceTextures), firstValue(model.textures));
    }

    private BufferedImage loadTexture(String reference,
                                      Map<String, String> textures,
                                      String defaultNamespace,
                                      ResourceIndex index) throws IOException {
        String dereferenced = dereference(reference, textures, 0);
        Identifier textureId = Identifier.parse(dereferenced, defaultNamespace);
        if (textureId == null) return null;
        Resource resource = findTextureResource(textureId, index);
        return resource == null ? null : readTexture(resource);
    }

    private Resource findTextureResource(Identifier textureId, ResourceIndex index) {
        List<String> paths = new ArrayList<String>();
        paths.add(textureId.path);
        if (textureId.path.startsWith("item/")) paths.add("items/" + textureId.path.substring(5));
        if (textureId.path.startsWith("items/")) paths.add("item/" + textureId.path.substring(6));
        if (textureId.path.startsWith("block/")) paths.add("blocks/" + textureId.path.substring(6));
        if (textureId.path.startsWith("blocks/")) paths.add("block/" + textureId.path.substring(7));
        for (String path : paths) {
            Resource resource = index.resources.get("assets/" + textureId.namespace
                    + "/textures/" + path + ".png");
            if (resource != null) return resource;
        }
        return null;
    }

    private BufferedImage readRawTexture(Resource resource) throws IOException {
        return decodeImage(resource.read(MAX_IMAGE_BYTES));
    }

    private BufferedImage readTexture(Resource resource) throws IOException {
        BufferedImage image = decodeImage(resource.read(MAX_IMAGE_BYTES));
        return image == null ? null : firstAnimationFrame(image);
    }

    private BufferedImage decodeImage(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) return null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * (long) height;
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION
                        || height > MAX_IMAGE_DIMENSION || pixels > MAX_IMAGE_PIXELS) {
                    throw new IOException("Image dimensions exceed safety limit: "
                            + width + "x" + height);
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage firstAnimationFrame(BufferedImage image) {
        if (image == null) return null;
        int side = Math.min(image.getWidth(), image.getHeight());
        if (side <= 0) return image;
        if (image.getWidth() == side && image.getHeight() == side) return image;
        return image.getSubimage(0, 0, side, side);
    }

    private BufferedImage normalizeIcon(BufferedImage image) {
        if (image == null) return null;
        BufferedImage frame = firstAnimationFrame(image);
        if (frame.getWidth() == ICON_SIZE && frame.getHeight() == ICON_SIZE) return frame;
        BufferedImage result = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(frame, 0, 0, ICON_SIZE, ICON_SIZE, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private String dereference(String value, Map<String, String> textures, int depth) {
        if (value == null || depth > MAX_MODEL_DEPTH) return null;
        if (!value.startsWith("#")) return value;
        return dereference(textures.get(value.substring(1)), textures, depth + 1);
    }

    private CompletableFuture<ResourceIndex> getIndexFuture(final Executor executor) {
        CompletableFuture<ResourceIndex> existing = indexFuture.get();
        if (existing != null) return existing;
        lastSourceCheck = System.currentTimeMillis();
        CompletableFuture<ResourceIndex> created = CompletableFuture.supplyAsync(
                new java.util.function.Supplier<ResourceIndex>() {
                    @Override
                    public ResourceIndex get() {
                        try {
                            return buildIndex(discoverSources());
                        } catch (Throwable throwable) {
                            platform.warn("Inventory resource index failed: " + safeMessage(throwable));
                            return new ResourceIndex(Collections.<String, Resource>emptyMap(),
                                    generation.incrementAndGet(), Long.MIN_VALUE);
                        }
                    }
                }, executor);
        if (indexFuture.compareAndSet(null, created)) return created;
        return indexFuture.get();
    }

    /**
     * Checks source fingerprints on the dedicated resource thread. The currently
     * completed index remains visible while a replacement is built, so a refresh
     * can never temporarily turn every slot into a missing-texture placeholder.
     */
    private void refreshIfNeeded() {
        final Executor executor = indexExecutor;
        if (executor == null) return;
        long now = System.currentTimeMillis();
        long interval = settings.getResourceRefreshSeconds() * 1000L;
        if (now - lastSourceCheck < interval
                || !sourceCheckRunning.compareAndSet(false, true)) return;
        lastSourceCheck = now;

        CompletableFuture.supplyAsync(new java.util.function.Supplier<ResourceIndex>() {
            @Override
            public ResourceIndex get() {
                try {
                    SourceSet sources = discoverSources();
                    if (sources.fingerprint == lastSourceFingerprint) return null;
                    return buildIndex(sources);
                } catch (Throwable throwable) {
                    platform.warn("Inventory resource refresh failed: " + safeMessage(throwable));
                    return null;
                }
            }
        }, executor).whenComplete(new java.util.function.BiConsumer<ResourceIndex, Throwable>() {
            @Override
            public void accept(ResourceIndex replacement, Throwable throwable) {
                if (replacement != null) {
                    indexFuture.set(CompletableFuture.completedFuture(replacement));
                }
                sourceCheckRunning.set(false);
            }
        });
    }

    private SourceSet discoverSources() throws IOException {
        LinkedHashSet<Path> ordered = new LinkedHashSet<Path>();
        for (String configured : settings.getResourceArchives()) {
            Path path = resolve(configured);
            if (isResourceSource(path)) {
                ordered.add(realOrNormalized(path));
            } else if (Files.isDirectory(path)) {
                addDirectorySources(ordered, path, false);
            }
        }

        if (settings.isAutoDiscoverResources()) {
            Path serverRoot = guessedServerRoot();
            addDirectorySources(ordered, dataDirectory.resolve("resources"), false);
            addDirectorySources(ordered, dataDirectory.resolve("resourcepacks"), true);
            addDirectorySources(ordered, serverRoot.resolve("resourcepacks"), true);
            addDirectorySources(ordered, serverRoot.resolve("server-resource-packs"), true);
        }

        if (settings.isScanModJars()) {
            addDirectorySources(ordered, resolve(settings.getModsDirectory()), false);
        }

        if (settings.isAutoDiscoverResources()) {
            Path serverRoot = guessedServerRoot();
            addVersionDirectorySources(ordered, serverRoot.resolve("versions"));
            addNamedClientJars(ordered, serverRoot);
            String userHome = System.getProperty("user.home", "").trim();
            if (!userHome.isEmpty()) {
                addVersionDirectorySources(ordered,
                        java.nio.file.Paths.get(userHome).resolve(".minecraft").resolve("versions"));
            }
        }

        List<Path> sources = new ArrayList<Path>(ordered);
        if (sources.size() > MAX_RESOURCE_SOURCES) {
            platform.warn("Inventory resource source limit reached: using the first "
                    + MAX_RESOURCE_SOURCES + " of " + sources.size() + " sources");
            sources = new ArrayList<Path>(sources.subList(0, MAX_RESOURCE_SOURCES));
        }
        long fingerprint = 1125899906842597L;
        for (Path source : sources) {
            long modified = safeLastModified(source);
            long size = Files.isRegularFile(source) ? safeSize(source) : directoryFingerprint(source);
            fingerprint = 31L * fingerprint + source.toString().hashCode();
            fingerprint = 31L * fingerprint + modified;
            fingerprint = 31L * fingerprint + size;
        }
        fingerprint = 31L * fingerprint + directoryFingerprint(exportedDirectory);
        return new SourceSet(sources, fingerprint);
    }

    private ResourceIndex buildIndex(SourceSet sourceSet) throws IOException {
        final Map<String, Resource> resources = new LinkedHashMap<String, Resource>();
        for (Path source : sourceSet.sources) {
            try {
                if (Files.isDirectory(source)) indexDirectory(source, resources);
                else indexArchive(source, resources);
            } catch (Throwable throwable) {
                platform.warn("Skipping inventory resource source " + source + ": " + safeMessage(throwable));
            }
        }
        long nextGeneration = generation.incrementAndGet();
        lastSourceFingerprint = sourceSet.fingerprint;
        int vanillaTextures = countResources(resources, "assets/minecraft/textures/", ".png");
        int vanillaModels = countResources(resources, "assets/minecraft/models/", ".json")
                + countResources(resources, "assets/minecraft/items/", ".json");
        platform.info("Inventory icon resource index contains " + resources.size()
                + " assets from " + sourceSet.sources.size() + " sources"
                + " (minecraft textures=" + vanillaTextures + ", models=" + vanillaModels + ")");
        int sourceLimit = Math.min(20, sourceSet.sources.size());
        for (int i = 0; i < sourceLimit; i++) {
            platform.info("Inventory icon source " + (i + 1) + ": " + sourceSet.sources.get(i));
        }
        if (sourceSet.sources.size() > sourceLimit) {
            platform.info("Inventory icon sources: " + (sourceSet.sources.size() - sourceLimit)
                    + " additional sources omitted from the log");
        }
        if (vanillaTextures == 0) {
            platform.warn("No assets/minecraft textures were found. Add the matching client JAR "
                    + "or a complete vanilla resource pack to inventory.icons.resource-archives/mods-directory.");
        } else if (vanillaTextures < 100) {
            platform.warn("Only " + vanillaTextures + " minecraft textures were indexed. This looks like "
                    + "an overlay resource pack; add the matching full client JAR as a lower-priority source.");
        }
        return new ResourceIndex(resources, nextGeneration, sourceSet.fingerprint);
    }

    private int countResources(Map<String, Resource> resources, String prefix, String suffix) {
        int count = 0;
        for (String path : resources.keySet()) {
            if (path.startsWith(prefix) && path.endsWith(suffix)) count++;
        }
        return count;
    }

    private void indexDirectory(final Path root, final Map<String, Resource> resources) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (resources.size() >= MAX_INDEX_RESOURCES) return FileVisitResult.TERMINATE;
                String relative = normalizeResourcePath(root.relativize(file).toString());
                String assetPath = extractAssetPath(relative);
                if (isSupportedResource(assetPath) && !resources.containsKey(assetPath)) {
                    resources.put(assetPath, Resource.file(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void indexArchive(Path archive, Map<String, Resource> resources) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (resources.size() >= MAX_INDEX_RESOURCES) break;
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String assetPath = extractAssetPath(normalizeResourcePath(entry.getName()));
                if (isSupportedResource(assetPath) && !resources.containsKey(assetPath)) {
                    resources.put(assetPath, Resource.archive(archive, entry.getName()));
                }
            }
        }
    }

    private String extractAssetPath(String path) {
        if (path.startsWith("assets/")) return path;
        int index = path.indexOf("/assets/");
        return index < 0 ? path : path.substring(index + 1);
    }

    private boolean isSupportedResource(String path) {
        return path.length() <= 1024 && path.startsWith("assets/")
                && (path.endsWith(".json") || path.endsWith(".png"));
    }

    private void addDirectorySources(Set<Path> output, Path directory, boolean reversePriority) throws IOException {
        if (!Files.isDirectory(directory)) return;
        List<Path> sources = new ArrayList<Path>();
        if (Files.isDirectory(directory.resolve("assets"))) sources.add(directory);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (isResourceSource(path)) sources.add(realOrNormalized(path));
            }
        }
        Collections.sort(sources, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.toString().compareToIgnoreCase(right.toString());
            }
        });
        if (reversePriority) Collections.reverse(sources);
        output.addAll(sources);
    }

    private void addVersionDirectorySources(Set<Path> output, Path versions) throws IOException {
        if (!Files.isDirectory(versions)) return;
        List<Path> candidates = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versions)) {
            for (Path version : stream) {
                if (Files.isRegularFile(version) && isArchive(version)) {
                    candidates.add(version);
                } else if (Files.isDirectory(version)) {
                    try (DirectoryStream<Path> nested = Files.newDirectoryStream(version)) {
                        for (Path path : nested) {
                            if (Files.isRegularFile(path) && isArchive(path)) candidates.add(path);
                        }
                    }
                }
            }
        }
        Collections.sort(candidates, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return right.toString().compareToIgnoreCase(left.toString());
            }
        });
        for (Path candidate : candidates) output.add(realOrNormalized(candidate));
    }

    private void addNamedClientJars(Set<Path> output, Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path) || !isArchive(path)) continue;
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.contains("client") || name.equals("minecraft.jar") || name.matches("[0-9].*\\.jar")) {
                    output.add(realOrNormalized(path));
                }
            }
        }
    }

    private boolean isResourceSource(Path path) {
        return Files.isDirectory(path) ? Files.isDirectory(path.resolve("assets")) : Files.isRegularFile(path) && isArchive(path);
    }

    private boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private Path guessedServerRoot() {
        Path parent = dataDirectory.getParent();
        if (parent != null && parent.getFileName() != null
                && "plugins".equalsIgnoreCase(parent.getFileName().toString()) && parent.getParent() != null) {
            return parent.getParent().toAbsolutePath().normalize();
        }
        return dataDirectory;
    }

    private long directoryFingerprint(final Path directory) {
        if (!Files.isDirectory(directory)) return 0L;
        final long[] result = {safeLastModified(directory)};
        final int[] visited = {0};
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (visited[0]++ >= 100000) return FileVisitResult.TERMINATE;
                    String name = file.getFileName() == null
                            ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".json") || name.endsWith(".png")
                            || name.endsWith(".jar") || name.endsWith(".zip")) {
                        result[0] = 31L * result[0]
                                + directory.relativize(file).toString().hashCode();
                        result[0] = 31L * result[0] + attrs.lastModifiedTime().toMillis();
                        result[0] = 31L * result[0] + attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return result[0];
        }
        return 31L * result[0] + visited[0];
    }

    private BufferedImage readImage(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(exportedDirectory) || !Files.isRegularFile(normalized)
                    || Files.size(normalized) > MAX_IMAGE_BYTES) return null;
            return decodeImage(Files.readAllBytes(normalized));
        } catch (Throwable throwable) {
            platform.warn("Failed to read exported inventory icon " + path + ": " + safeMessage(throwable));
            return null;
        }
    }

    private Path resolve(String configured) {
        String value = configured == null ? "" : configured.trim();
        Path path = java.nio.file.Paths.get(value.isEmpty() ? "." : value);
        return path.isAbsolute() ? path.normalize() : dataDirectory.resolve(path).normalize();
    }

    private Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private long safeLastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return 0L; }
    }

    private long safeSize(Path path) {
        try { return Files.size(path); }
        catch (IOException ignored) { return 0L; }
    }

    private static String normalizeResourcePath(String value) {
        return value.replace('\\', '/').replaceAll("^/+", "").toLowerCase(Locale.ROOT);
    }

    private void warnOnce(String key, String message) {
        synchronized (warningCache) {
            if (warningCache.containsKey(key)) return;
            warningCache.put(key, Boolean.TRUE);
        }
        platform.warn(message);
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "unknown error";
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String firstValue(Map<String, String> values) {
        for (String value : values.values()) {
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }

    private static BufferedImage firstNonNull(BufferedImage... images) {
        for (BufferedImage image : images) if (image != null) return image;
        return null;
    }

    private static void addUnique(List<Identifier> values, Identifier value) {
        if (value != null && !values.contains(value)) values.add(value);
    }

    private static Object[] createLocks(int count) {
        Object[] locks = new Object[Math.max(1, count)];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Point {
        private final int x;
        private final int y;
        private Point(int x, int y) { this.x = x; this.y = y; }
    }

    private static final class Tex {
        private final double u;
        private final double v;
        private Tex(double u, double v) { this.u = u; this.v = v; }
    }

    private static final class CacheValue {
        private final BufferedImage image;
        private final long generation;
        private final long expiresAt;
        private CacheValue(BufferedImage image, long generation, long expiresAt) {
            this.image = image;
            this.generation = generation;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Model {
        private final Map<String, String> textures = new LinkedHashMap<String, String>();
        private final Map<String, String> faceTextures = new LinkedHashMap<String, String>();
        private boolean flat;
        private boolean hasElements;

        private static Model empty() { return new Model(); }

        private Model copy() {
            Model copy = new Model();
            copy.textures.putAll(textures);
            copy.faceTextures.putAll(faceTextures);
            copy.flat = flat;
            copy.hasElements = hasElements;
            return copy;
        }
    }

    private static final class SourceSet {
        private final List<Path> sources;
        private final long fingerprint;
        private SourceSet(List<Path> sources, long fingerprint) {
            this.sources = Collections.unmodifiableList(new ArrayList<Path>(sources));
            this.fingerprint = fingerprint;
        }
    }

    private static final class ResourceIndex {
        private final Map<String, Resource> resources;
        private final long generation;
        private final long fingerprint;

        private ResourceIndex(Map<String, Resource> resources, long generation, long fingerprint) {
            this.resources = Collections.unmodifiableMap(new LinkedHashMap<String, Resource>(resources));
            this.generation = generation;
            this.fingerprint = fingerprint;
        }

        private static ResourceIndex empty() {
            return new ResourceIndex(Collections.<String, Resource>emptyMap(), 0L, 0L);
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
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry zipEntry = zip.getEntry(entry);
                if (zipEntry == null || zipEntry.isDirectory() || zipEntry.getSize() > maximumBytes) {
                    throw new IOException("Invalid archive resource: " + entry);
                }
                try (InputStream input = zip.getInputStream(zipEntry)) {
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
                }
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
            if (value == null) return null;
            String clean = value.trim().toLowerCase(Locale.ROOT);
            if (clean.isEmpty() || clean.startsWith("#")) return null;
            int colon = clean.indexOf(':');
            String namespace = colon < 0 ? defaultNamespace : clean.substring(0, colon);
            String path = colon < 0 ? clean : clean.substring(colon + 1);
            if (namespace == null || !namespace.matches("[a-z0-9_.-]+")
                    || !path.matches("[a-z0-9_./-]+") || path.contains("..")) return null;
            return new Identifier(namespace, path);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Identifier)) return false;
            Identifier value = (Identifier) other;
            return namespace.equals(value.namespace) && path.equals(value.path);
        }

        @Override
        public int hashCode() { return 31 * namespace.hashCode() + path.hashCode(); }

        @Override
        public String toString() { return namespace + ':' + path; }
    }
}
