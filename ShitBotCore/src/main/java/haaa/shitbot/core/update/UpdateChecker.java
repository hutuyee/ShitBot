package haaa.shitbot.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks GitHub Releases without ever performing network access on a platform thread.
 * The last successful release response is retained on disk and only rewritten when it changes.
 */
public final class UpdateChecker implements AutoCloseable {
    public static final String RELEASES_URL = "https://github.com/hutuyee/ShitBot/releases/latest";

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/hutuyee/ShitBot/releases/latest";
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 5000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_CACHE_BYTES = 64 * 1024;

    private final String currentVersion;
    private final Path cachePath;
    private final PlatformBridge platform;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("shitbot-update", true));
    private final AtomicReference<UpdateInfo> latestRelease = new AtomicReference<UpdateInfo>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private CompletableFuture<UpdateInfo> inFlight;

    public UpdateChecker(String currentVersion, PlatformBridge platform) {
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
        this.platform = platform;
        this.cachePath = platform.getDataDirectory().resolve("update-cache.json");
        latestRelease.set(readCache());
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public UpdateInfo getCachedRelease() {
        return latestRelease.get();
    }

    public boolean isUpdateAvailable(UpdateInfo info) {
        return info != null && info.isUpdateAvailable(currentVersion);
    }

    /** Starts or joins a real network check on the dedicated update worker. */
    public synchronized CompletableFuture<UpdateInfo> checkAsync() {
        if (closed.get()) {
            return FutureUtil.failedFuture(new IllegalStateException("Update checker is closed"));
        }
        if (inFlight != null && !inFlight.isDone()) {
            return inFlight;
        }

        final CompletableFuture<UpdateInfo> created;
        try {
            created = CompletableFuture.supplyAsync(
                    new java.util.function.Supplier<UpdateInfo>() {
                        @Override
                        public UpdateInfo get() {
                            try {
                                return fetchAndCache();
                            } catch (IOException exception) {
                                throw new java.util.concurrent.CompletionException(exception);
                            }
                        }
                    }, executor);
        } catch (RejectedExecutionException exception) {
            return FutureUtil.failedFuture(exception);
        }
        inFlight = created;
        created.whenComplete(new java.util.function.BiConsumer<UpdateInfo, Throwable>() {
            @Override
            public void accept(UpdateInfo ignored, Throwable throwable) {
                clearInFlight(created);
            }
        });
        return created;
    }

    /**
     * Uses the active startup check when one exists, falling back to the disk cache on failure.
     * This lets a joining administrator wait asynchronously for startup discovery without causing
     * another request or blocking the login thread.
     */
    public CompletableFuture<UpdateInfo> latestForNotificationAsync() {
        final CompletableFuture<UpdateInfo> active;
        synchronized (this) {
            active = inFlight;
        }
        if (active == null) {
            return CompletableFuture.completedFuture(latestRelease.get());
        }
        return active.handle(new java.util.function.BiFunction<UpdateInfo, Throwable, UpdateInfo>() {
            @Override
            public UpdateInfo apply(UpdateInfo fresh, Throwable throwable) {
                return fresh == null ? latestRelease.get() : fresh;
            }
        });
    }

    private synchronized void clearInFlight(CompletableFuture<UpdateInfo> completed) {
        if (inFlight == completed) {
            inFlight = null;
        }
    }

    private UpdateInfo fetchAndCache() throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "ShitBot-UpdateChecker/" + currentVersion);
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub release request returned HTTP " + status);
            }

            JsonNode root;
            try (InputStream input = connection.getInputStream()) {
                root = objectMapper.readTree(readLimited(input, MAX_RESPONSE_BYTES));
            }
            UpdateInfo fetched = parseRelease(root);
            UpdateInfo previous = latestRelease.getAndSet(fetched);
            if (!fetched.isSameRelease(previous)) {
                try {
                    writeCache(fetched);
                } catch (IOException cacheFailure) {
                    platform.warn("Unable to store update cache: " + cacheFailure.getMessage());
                }
            }
            return fetched;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private UpdateInfo parseRelease(JsonNode root) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IOException("GitHub release response is not a JSON object");
        }
        String version = root.path("tag_name").asText("").trim();
        if (version.isEmpty()) {
            throw new IOException("GitHub release response has no tag_name");
        }
        String releaseUrl = root.path("html_url").asText("").trim();
        if (releaseUrl.isEmpty()) {
            releaseUrl = RELEASES_URL;
        }
        return new UpdateInfo(version, releaseUrl, root.path("published_at").asText(""));
    }

    private UpdateInfo readCache() {
        try {
            if (!Files.isRegularFile(cachePath)) {
                return null;
            }
            long size = Files.size(cachePath);
            if (size <= 0L || size > MAX_CACHE_BYTES) {
                throw new IOException("cache size is invalid");
            }
            JsonNode root = objectMapper.readTree(Files.readAllBytes(cachePath));
            if (root == null || !root.isObject()) {
                throw new IOException("cache is not a JSON object");
            }
            String version = root.path("latestVersion").asText("").trim();
            if (version.isEmpty()) {
                throw new IOException("cache has no latestVersion");
            }
            String releaseUrl = root.path("releaseUrl").asText(RELEASES_URL).trim();
            if (releaseUrl.isEmpty()) {
                releaseUrl = RELEASES_URL;
            }
            return new UpdateInfo(version, releaseUrl, root.path("publishedAt").asText(""));
        } catch (IOException exception) {
            platform.warn("Unable to read update cache: " + exception.getMessage());
            return null;
        }
    }

    private void writeCache(UpdateInfo info) throws IOException {
        Path parent = cachePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("latestVersion", info.getLatestVersion());
        root.put("releaseUrl", info.getReleaseUrl());
        root.put("publishedAt", info.getPublishedAt());

        Path temporary = cachePath.resolveSibling(cachePath.getFileName().toString() + ".tmp");
        Files.write(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        try {
            Files.move(temporary, cachePath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, cachePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("GitHub release response is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }
}
