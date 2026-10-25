package haaa.shitbot.renderer;

import haaa.shitbot.api.spi.ImageTemplateEngineSettings;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.net.ssl.HttpsURLConnection;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class SecureImageLoader implements AutoCloseable {
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final long MAX_CACHE_PIXELS = 16L * 1024L * 1024L;

    private final ImageTemplateEngineSettings settings;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(16), new RendererThreadFactory("assets"),
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, CompletableFuture<BufferedImage>> remoteCache =
            new LinkedHashMap<String, CompletableFuture<BufferedImage>>(16, 0.75F, true);

    SecureImageLoader(ImageTemplateEngineSettings settings) {
        this.settings = settings;
    }

    BufferedImage load(TemplateSnapshot snapshot, Object source) throws IOException {
        if (source == null) return null;
        if (source instanceof BufferedImage) {
            BufferedImage image = (BufferedImage) source;
            validateDimensions(image.getWidth(), image.getHeight());
            return image;
        }
        if (source instanceof byte[]) return decode((byte[]) source);
        String value = String.valueOf(source).trim();
        if (value.isEmpty()) return null;
        if (value.startsWith("data:image/")) return dataImage(value);
        if (value.startsWith("https://")) return remote(value);
        if (value.contains("://")) throw new IOException("Only HTTPS remote images are supported");
        return local(snapshot, value);
    }

    private BufferedImage local(TemplateSnapshot snapshot, String value) throws IOException {
        String name = value.replace('\\', '/');
        if (name.startsWith("assets/")) name = name.substring("assets/".length());
        if (name.isEmpty() || name.startsWith("/") || name.equals("..") || name.startsWith("../")
                || name.contains("/../") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Invalid template asset path");
        }
        Path assets = snapshot.getDirectory().resolve("assets").toAbsolutePath().normalize();
        Path file = assets.resolve(name).toAbsolutePath().normalize();
        if (!file.startsWith(assets) || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IOException("Template asset is unavailable or unsafe: " + value);
        }
        Path current = file.getParent();
        while (current != null && current.startsWith(assets)) {
            if (Files.isSymbolicLink(current)) throw new IOException("Template asset path contains a symbolic link");
            current = current.getParent();
        }
        long size = Files.size(file);
        if (size <= 0L || size > settings.getMaximumAssetBytes()) {
            throw new IOException("Template asset exceeds its size limit");
        }
        return decode(Files.readAllBytes(file));
    }

    private BufferedImage dataImage(String value) throws IOException {
        int marker = value.indexOf(";base64,");
        if (marker < 0) throw new IOException("Image data URI must use base64");
        String mediaType = value.substring(5, marker).toLowerCase(Locale.ROOT);
        if (!mediaType.equals("image/png") && !mediaType.equals("image/jpeg")
                && !mediaType.equals("image/gif")) {
            throw new IOException("Unsupported image data URI type");
        }
        long maximumEncodedLength = ((settings.getMaximumAssetBytes() + 2L) / 3L) * 4L;
        if ((long) value.length() - marker - 8L > maximumEncodedLength) {
            throw new IOException("Image asset has an invalid size");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value.substring(marker + 8));
            return decode(bytes);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Image data URI is malformed", exception);
        }
    }

    private BufferedImage remote(final String value) throws IOException {
        if (!settings.isRemoteImagesEnabled()) {
            throw new IOException("Remote template images are disabled");
        }
        final CompletableFuture<BufferedImage> future;
        synchronized (remoteCache) {
            CompletableFuture<BufferedImage> existing = remoteCache.get(value);
            if (existing != null) {
                future = existing;
            } else {
                final long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(Math.max(500, settings.getRenderTimeoutMillis()));
                try {
                    future = CompletableFuture.supplyAsync(new java.util.function.Supplier<BufferedImage>() {
                        @Override
                        public BufferedImage get() {
                            try {
                                return download(value, deadline);
                            } catch (IOException exception) {
                                throw new java.util.concurrent.CompletionException(exception);
                            }
                        }
                    }, executor);
                } catch (RejectedExecutionException exception) {
                    throw new IOException("Remote image download queue is full or closed", exception);
                }
                remoteCache.put(value, future);
                while (remoteCache.size() > MAX_CACHE_ENTRIES) {
                    remoteCache.remove(remoteCache.keySet().iterator().next());
                }
                future.whenComplete(new java.util.function.BiConsumer<BufferedImage, Throwable>() {
                    @Override
                    public void accept(BufferedImage image, Throwable throwable) {
                        synchronized (remoteCache) {
                            if (throwable != null) {
                                remoteCache.remove(value, future);
                            } else {
                                trimRemoteCache();
                            }
                        }
                    }
                });
            }
        }
        try {
            return future.get(Math.max(500, settings.getRenderTimeoutMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Remote image load was interrupted", exception);
        } catch (TimeoutException exception) {
            throw new IOException("Remote image load timed out", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Remote image load failed", cause);
        }
    }

    private void trimRemoteCache() {
        long pixels = 0L;
        for (CompletableFuture<BufferedImage> cached : remoteCache.values()) {
            pixels += cachedPixels(cached);
        }
        Iterator<CompletableFuture<BufferedImage>> oldest = remoteCache.values().iterator();
        while (pixels > MAX_CACHE_PIXELS && oldest.hasNext()) {
            CompletableFuture<BufferedImage> cached = oldest.next();
            if (!cached.isDone()) continue;
            pixels -= cachedPixels(cached);
            oldest.remove();
        }
    }

    private long cachedPixels(CompletableFuture<BufferedImage> future) {
        if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) return 0L;
        BufferedImage image = future.getNow(null);
        return image == null ? 0L : (long) image.getWidth() * image.getHeight();
    }

    private BufferedImage download(String value, long deadline) throws IOException {
        requireBeforeDeadline(deadline);
        URL current = safeRemoteUrl(value);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            requireBeforeDeadline(deadline);
            InetAddress address = safeRemoteAddress(current);
            requireBeforeDeadline(deadline);
            HttpsURLConnection connection = (HttpsURLConnection) current.openConnection(Proxy.NO_PROXY);
            connection.setSSLSocketFactory(new PinnedHttpsSocketFactory(
                    connection.getSSLSocketFactory(), current.getHost(), address, 1500, 2500, deadline));
            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(2500);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif");
                connection.setRequestProperty("User-Agent", "ShitBotRenderer/1");
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_OK) {
                    String contentType = connection.getContentType();
                    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                        throw new IOException("Remote image returned a non-image content type");
                    }
                    if (connection.getContentLengthLong() > settings.getMaximumAssetBytes()) {
                        throw new IOException("Remote image exceeds its size limit");
                    }
                    try (InputStream input = connection.getInputStream()) {
                        byte[] bytes = readLimited(input, settings.getMaximumAssetBytes(), deadline);
                        requireBeforeDeadline(deadline);
                        return decode(bytes);
                    }
                }
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    throw new IOException("Remote image returned HTTP " + status);
                }
                String location = connection.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Remote image redirect has no Location header");
                }
                current = safeRemoteUrl(new URL(current, location).toString());
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Remote image has too many redirects");
    }

    private URL safeRemoteUrl(String value) throws IOException {
        final URL url;
        try {
            url = new URL(value);
        } catch (Exception exception) {
            throw new IOException("Remote image URL is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null || url.getHost().isEmpty()
                || (url.getPort() != -1 && url.getPort() != 443)) {
            throw new IOException("Remote image URL must use HTTPS without credentials");
        }
        return url;
    }

    private InetAddress safeRemoteAddress(URL url) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
        if (addresses.length == 0) throw new IOException("Remote image host has no address");
        for (InetAddress address : addresses) {
            if (unsafe(address)) throw new IOException("Remote image host resolves to a private address");
        }
        return addresses[0];
    }

    private boolean unsafe(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 192 && second == 0 && bytes[2] == 0)
                    || first == 0 || first >= 224;
        }
        // Only native global IPv6 unicast is allowed; translation/tunnel prefixes
        // could otherwise encode an IPv4 destination that bypasses the checks above.
        return bytes.length != 16 || (bytes[0] & 0xe0) != 0x20
                || (bytes[0] == 0x20 && bytes[1] == 0x02)
                || (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0);
    }

    private BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > settings.getMaximumAssetBytes()) {
            throw new IOException("Image asset has an invalid size");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IOException("Unable to inspect image asset");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image asset format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                validateDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Unable to decode image asset");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private void validateDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > settings.getMaximumWidth()
                || height > settings.getMaximumHeight()
                || (long) width * height > settings.getMaximumPixels()) {
            throw new IOException("Image asset dimensions exceed configured limits");
        }
    }

    private byte[] readLimited(InputStream input, long maximumBytes, long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maximumBytes, 8192L));
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            requireBeforeDeadline(deadline);
            if (read == 0) continue;
            total += read;
            if (total > maximumBytes) throw new IOException("Image asset exceeds its size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void requireBeforeDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0L) {
            throw new IOException("Remote image download deadline expired");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        synchronized (remoteCache) {
            remoteCache.clear();
        }
    }
}
