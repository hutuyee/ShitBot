package haaa.shitbot.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private static final int MAX_CHECKSUM_BYTES = 4096;
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final long MAX_PLUGIN_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_RELEASE_ASSETS = 64;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{64})(?:\\s+\\*?(.+))?$");
    private static final Pattern YAML_VERSION_PATTERN = Pattern.compile(
            "(?m)^\\s*version\\s*:\\s*['\"]?([^'\"\\r\\n#]+)");

    private final String currentVersion;
    private final Path cachePath;
    private final PlatformBridge platform;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("shitbot-update", true));
    private final AtomicReference<UpdateInfo> latestRelease = new AtomicReference<UpdateInfo>();
    private final AtomicReference<String> installedVersion = new AtomicReference<String>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private CompletableFuture<UpdateInfo> inFlight;
    private CompletableFuture<UpdateInstallResult> installInFlight;

    public UpdateChecker(String currentVersion, PlatformBridge platform) {
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
        this.platform = platform;
        this.cachePath = platform.getDataDirectory().resolve("update-cache.json");
        this.installedVersion.set(this.currentVersion);
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

    /** Fetches the latest Release and installs the matching platform JAR when needed. */
    public CompletableFuture<UpdateInstallResult> updateAsync(final UpdatePlatform updatePlatform,
                                                              final Path pluginJar) {
        return checkAsync().thenCompose(
                new java.util.function.Function<UpdateInfo, CompletableFuture<UpdateInstallResult>>() {
                    @Override
                    public CompletableFuture<UpdateInstallResult> apply(UpdateInfo info) {
                        return installReleaseAsync(info, updatePlatform, pluginJar);
                    }
                });
    }

    /** Installs already-discovered Release metadata, used by authenticated backend coordination. */
    public synchronized CompletableFuture<UpdateInstallResult> installReleaseAsync(
            final UpdateInfo info,
            final UpdatePlatform updatePlatform,
            final Path pluginJar) {
        if (closed.get()) {
            return FutureUtil.failedFuture(new IllegalStateException("Update checker is closed"));
        }
        if (info == null || updatePlatform == null || pluginJar == null) {
            return FutureUtil.failedFuture(new IllegalArgumentException("Update install request is incomplete"));
        }
        if (!info.isUpdateAvailable(currentVersion)) {
            return CompletableFuture.completedFuture(UpdateInstallResult.upToDate(
                    currentVersion, info.getLatestVersion(), pluginJar.toAbsolutePath().normalize()));
        }
        if (!info.isUpdateAvailable(installedVersion.get())) {
            return CompletableFuture.completedFuture(UpdateInstallResult.alreadyInstalled(
                    currentVersion, info.getLatestVersion(), pluginJar.toAbsolutePath().normalize()));
        }
        if (installInFlight != null && !installInFlight.isDone()) {
            return installInFlight;
        }

        final ReleaseAsset jarAsset = info.findJarAsset(updatePlatform);
        if (jarAsset == null) {
            return FutureUtil.failedFuture(new IOException("Release does not contain exactly one "
                    + updatePlatform.getAssetPrefix() + "*.jar asset"));
        }
        final ReleaseAsset checksumAsset = info.findAsset(jarAsset.getName() + ".sha256");
        if (checksumAsset == null) {
            return FutureUtil.failedFuture(new IOException("Release is missing checksum asset "
                    + jarAsset.getName() + ".sha256"));
        }

        final CompletableFuture<UpdateInstallResult> created;
        try {
            created = CompletableFuture.supplyAsync(
                    new java.util.function.Supplier<UpdateInstallResult>() {
                        @Override
                        public UpdateInstallResult get() {
                            try {
                                return installRelease(info, updatePlatform, pluginJar,
                                        jarAsset, checksumAsset);
                            } catch (IOException exception) {
                                throw new java.util.concurrent.CompletionException(exception);
                            }
                        }
                    }, executor);
        } catch (RejectedExecutionException exception) {
            return FutureUtil.failedFuture(exception);
        }
        installInFlight = created;
        created.whenComplete(new java.util.function.BiConsumer<UpdateInstallResult, Throwable>() {
            @Override
            public void accept(UpdateInstallResult ignored, Throwable throwable) {
                clearInstallInFlight(created);
            }
        });
        return created;
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

    private synchronized void clearInstallInFlight(CompletableFuture<UpdateInstallResult> completed) {
        if (installInFlight == completed) {
            installInFlight = null;
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
        return new UpdateInfo(version, releaseUrl, root.path("published_at").asText(""),
                parseAssets(root.path("assets")));
    }

    private List<ReleaseAsset> parseAssets(JsonNode assetsNode) {
        List<ReleaseAsset> assets = new ArrayList<ReleaseAsset>();
        if (assetsNode == null || !assetsNode.isArray()) {
            return assets;
        }
        for (JsonNode assetNode : assetsNode) {
            if (assets.size() >= MAX_RELEASE_ASSETS) {
                break;
            }
            String name = assetNode.path("name").asText("").trim();
            String url = assetNode.path("browser_download_url").asText("").trim();
            if (!name.isEmpty() && !url.isEmpty()) {
                assets.add(new ReleaseAsset(name, url, assetNode.path("size").asLong(0L),
                        assetNode.path("digest").asText("")));
            }
        }
        return assets;
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
            return new UpdateInfo(version, releaseUrl, root.path("publishedAt").asText(""),
                    parseCachedAssets(root.path("assets")));
        } catch (IOException exception) {
            platform.warn("Unable to read update cache: " + exception.getMessage());
            return null;
        }
    }

    private List<ReleaseAsset> parseCachedAssets(JsonNode assetsNode) {
        List<ReleaseAsset> assets = new ArrayList<ReleaseAsset>();
        if (assetsNode == null || !assetsNode.isArray()) {
            return assets;
        }
        for (JsonNode assetNode : assetsNode) {
            if (assets.size() >= MAX_RELEASE_ASSETS) {
                break;
            }
            String name = assetNode.path("name").asText("").trim();
            String url = assetNode.path("downloadUrl").asText("").trim();
            if (!name.isEmpty() && !url.isEmpty()) {
                assets.add(new ReleaseAsset(name, url, assetNode.path("size").asLong(0L),
                        assetNode.path("digest").asText("")));
            }
        }
        return assets;
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
        ArrayNode assets = root.putArray("assets");
        for (ReleaseAsset asset : info.getAssets()) {
            ObjectNode assetNode = assets.addObject();
            assetNode.put("name", asset.getName());
            assetNode.put("downloadUrl", asset.getDownloadUrl());
            assetNode.put("size", asset.getSize());
            assetNode.put("digest", asset.getDigest());
        }

        Path temporary = cachePath.resolveSibling(cachePath.getFileName().toString() + ".tmp");
        Files.write(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        try {
            Files.move(temporary, cachePath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, cachePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private UpdateInstallResult installRelease(UpdateInfo info,
                                               UpdatePlatform updatePlatform,
                                               Path configuredPluginJar,
                                               ReleaseAsset jarAsset,
                                               ReleaseAsset checksumAsset) throws IOException {
        Path pluginJar = configuredPluginJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(pluginJar)) {
            throw new IOException("Current plugin JAR does not exist: " + pluginJar);
        }
        pluginJar = pluginJar.toRealPath();
        if (!pluginJar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Current plugin source is not a JAR: " + pluginJar);
        }
        if (jarAsset.getSize() <= 0L || jarAsset.getSize() > MAX_PLUGIN_BYTES) {
            throw new IOException("Release JAR size is invalid: " + jarAsset.getSize());
        }

        String expectedSha256 = downloadChecksum(checksumAsset, jarAsset);
        verifyApiDigest(jarAsset, expectedSha256);

        Path parent = pluginJar.getParent();
        if (parent == null) {
            throw new IOException("Current plugin JAR has no parent directory");
        }
        Path temporary = parent.resolve(".shitbot-update-" + UUID.randomUUID().toString() + ".tmp");
        try {
            String downloadedSha256 = downloadJar(jarAsset, temporary);
            if (!expectedSha256.equalsIgnoreCase(downloadedSha256)) {
                throw new IOException("Downloaded JAR SHA-256 does not match Release checksum");
            }
            validateJar(temporary, updatePlatform, info.getLatestVersion());

            Path backup = pluginJar.resolveSibling(pluginJar.getFileName().toString() + ".bak");
            Path backupTemporary = backup.resolveSibling(backup.getFileName().toString() + ".tmp");
            try {
                Files.copy(pluginJar, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
                moveReplacing(backupTemporary, backup);
            } finally {
                deleteQuietly(backupTemporary);
            }

            try {
                moveReplacing(temporary, pluginJar);
            } catch (IOException installFailure) {
                if (!Files.isRegularFile(pluginJar) && Files.isRegularFile(backup)) {
                    try {
                        Files.copy(backup, pluginJar, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException restoreFailure) {
                        installFailure.addSuppressed(restoreFailure);
                    }
                }
                throw installFailure;
            }
            installedVersion.set(info.getLatestVersion());
            return UpdateInstallResult.installed(currentVersion, info.getLatestVersion(),
                    jarAsset.getName(), downloadedSha256, pluginJar, backup);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private String downloadChecksum(ReleaseAsset checksumAsset, ReleaseAsset jarAsset) throws IOException {
        if (checksumAsset.getSize() <= 0L || checksumAsset.getSize() > MAX_CHECKSUM_BYTES) {
            throw new IOException("Release checksum size is invalid: " + checksumAsset.getSize());
        }
        byte[] bytes = downloadBytes(checksumAsset, MAX_CHECKSUM_BYTES);
        String checksumText = new String(bytes, StandardCharsets.US_ASCII).trim();
        Matcher matcher = CHECKSUM_PATTERN.matcher(checksumText);
        if (!matcher.matches()) {
            throw new IOException("Release checksum file has an invalid format");
        }
        String checksumFileName = matcher.group(2);
        if (checksumFileName != null) {
            String normalizedName = checksumFileName.trim().replace('\\', '/');
            int slash = normalizedName.lastIndexOf('/');
            if (slash >= 0) {
                normalizedName = normalizedName.substring(slash + 1);
            }
            if (!jarAsset.getName().equals(normalizedName)) {
                throw new IOException("Release checksum belongs to a different asset");
            }
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    private void verifyApiDigest(ReleaseAsset jarAsset, String expectedSha256) throws IOException {
        String digest = jarAsset.getDigest();
        if (digest.isEmpty()) {
            return;
        }
        String prefix = "sha256:";
        if (!digest.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            throw new IOException("Release asset uses an unsupported digest: " + digest);
        }
        String apiSha256 = digest.substring(prefix.length()).trim();
        if (!expectedSha256.equalsIgnoreCase(apiSha256)) {
            throw new IOException("Release checksum does not match GitHub asset digest");
        }
    }

    private String downloadJar(ReleaseAsset asset, Path target) throws IOException {
        MessageDigest digest = sha256Digest();
        long downloaded = 0L;
        HttpURLConnection connection = openAssetConnection(asset.getDownloadUrl());
        try {
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_PLUGIN_BYTES) {
                throw new IOException("Release JAR exceeds the download limit");
            }
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(target,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    downloaded += read;
                    if (downloaded > MAX_PLUGIN_BYTES || downloaded > asset.getSize()) {
                        throw new IOException("Release JAR is larger than its declared size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
        if (downloaded != asset.getSize()) {
            throw new IOException("Release JAR size mismatch: expected " + asset.getSize()
                    + " bytes, received " + downloaded);
        }
        return hex(digest.digest());
    }

    private byte[] downloadBytes(ReleaseAsset asset, int maximumBytes) throws IOException {
        HttpURLConnection connection = openAssetConnection(asset.getDownloadUrl());
        try {
            long contentLength = connection.getContentLengthLong();
            if (contentLength > maximumBytes) {
                throw new IOException("Release asset exceeds the download limit: " + asset.getName());
            }
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readLimited(input, maximumBytes);
            }
            if (asset.getSize() > 0L && bytes.length != asset.getSize()) {
                throw new IOException("Release asset size mismatch: " + asset.getName());
            }
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openAssetConnection(String downloadUrl) throws IOException {
        URL current = requireOfficialAssetUrl(downloadUrl, true);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "ShitBot-Updater/" + currentVersion);
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                return connection;
            }
            if (status != HttpURLConnection.HTTP_MOVED_PERM
                    && status != HttpURLConnection.HTTP_MOVED_TEMP
                    && status != HttpURLConnection.HTTP_SEE_OTHER
                    && status != 307
                    && status != 308) {
                connection.disconnect();
                throw new IOException("Release asset request returned HTTP " + status);
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.trim().isEmpty()) {
                throw new IOException("Release asset redirect has no Location header");
            }
            current = requireOfficialAssetUrl(new URL(current, location).toString(), false);
        }
        throw new IOException("Release asset has too many redirects");
    }

    private URL requireOfficialAssetUrl(String value, boolean initial) throws IOException {
        final URL url;
        try {
            url = new URL(value);
        } catch (Exception exception) {
            throw new IOException("Release asset URL is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || url.getUserInfo() != null
                || url.getPort() != -1) {
            throw new IOException("Release asset URL must use official HTTPS without credentials or a port");
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if ("github.com".equals(host)) {
            String path = url.getPath();
            if (path != null && path.startsWith("/hutuyee/ShitBot/releases/download/")) {
                return url;
            }
        }
        if (!initial && ("release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host))) {
            return url;
        }
        throw new IOException("Release asset URL is outside the ShitBot GitHub Release");
    }

    private void validateJar(Path jarPath,
                             UpdatePlatform updatePlatform,
                             String expectedVersion) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry descriptor = zipFile.getEntry(updatePlatform.getDescriptorPath());
            if (descriptor == null || descriptor.isDirectory()) {
                throw new IOException("Downloaded JAR is missing " + updatePlatform.getDescriptorPath());
            }
            if (zipFile.getEntry(updatePlatform.getMainClassPath()) == null) {
                throw new IOException("Downloaded JAR is missing the expected plugin main class");
            }
            byte[] descriptorBytes;
            try (InputStream input = zipFile.getInputStream(descriptor)) {
                descriptorBytes = readLimited(input, MAX_DESCRIPTOR_BYTES);
            }
            String embeddedVersion = descriptorVersion(updatePlatform, descriptorBytes);
            if (!normalizeVersion(expectedVersion).equalsIgnoreCase(normalizeVersion(embeddedVersion))) {
                throw new IOException("Downloaded JAR version " + embeddedVersion
                        + " does not match Release " + expectedVersion);
            }
        }
    }

    private String descriptorVersion(UpdatePlatform updatePlatform, byte[] descriptorBytes) throws IOException {
        if (updatePlatform == UpdatePlatform.VELOCITY) {
            JsonNode root = objectMapper.readTree(descriptorBytes);
            String version = root == null ? "" : root.path("version").asText("").trim();
            if (version.isEmpty()) {
                throw new IOException("Downloaded Velocity JAR has no version metadata");
            }
            return version;
        }
        String yaml = new String(descriptorBytes, StandardCharsets.UTF_8);
        Matcher matcher = YAML_VERSION_PATTERN.matcher(yaml);
        if (!matcher.find()) {
            throw new IOException("Downloaded JAR has no version metadata");
        }
        return matcher.group(1).trim();
    }

    private MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is not available", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
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
