package haaa.shitbot.core.image;

import haaa.shitbot.api.spi.ImageTemplateEngine;
import haaa.shitbot.api.spi.ImageTemplateEngineFactory;
import haaa.shitbot.api.spi.ImageTemplateEngineHost;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Downloads and verifies the optional renderer only after custom templates are enabled. */
final class RendererComponentLoader {
    private static final String PUBLIC_KEY_RESOURCE =
            "/haaa/shitbot/core/update/update-public-key.pem";
    private static final String SERVICE_ENTRY =
            "META-INF/services/haaa.shitbot.api.spi.ImageTemplateEngineFactory";
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_CHECKSUM_BYTES = 4096;
    private static final int MAX_SIGNATURE_BYTES = 16384;
    private static final int MAX_PUBLIC_KEY_BYTES = 16384;
    private static final Pattern CHECKSUM = Pattern.compile(
            "^([0-9a-fA-F]{64})(?:\\s+\\*?(.+))?$");

    private final Settings.CustomImages settings;
    private final PlatformBridge platform;

    RendererComponentLoader(Settings.CustomImages settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
    }

    LoadedRenderer load(ImageTemplateEngineSettings engineSettings,
                        ImageTemplateEngineHost host) throws IOException {
        String version = componentVersion();
        String fileName = "ShitBotRenderer-" + version + ".jar";
        Path directory = platform.getDataDirectory().resolve("components")
                .resolve("image-renderer").resolve(version).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path jar = directory.resolve(fileName);
        Path checksum = directory.resolve(fileName + ".sha256");
        Path signature = directory.resolve(fileName + ".sig");
        if (!isVerified(jar, checksum, signature, fileName, version)) {
            download(jar, checksum, signature, fileName, version);
        }
        verify(jar, checksum, signature, fileName, version);

        URLClassLoader classLoader = new URLClassLoader(
                new URL[] { jar.toUri().toURL() }, ImageTemplateEngineFactory.class.getClassLoader());
        try {
            ServiceLoader<ImageTemplateEngineFactory> services = ServiceLoader.load(
                    ImageTemplateEngineFactory.class, classLoader);
            Iterator<ImageTemplateEngineFactory> iterator = services.iterator();
            if (!iterator.hasNext()) {
                throw new IOException("Renderer component does not provide an image template engine");
            }
            ImageTemplateEngineFactory factory = iterator.next();
            if (iterator.hasNext()) {
                throw new IOException("Renderer component provides more than one image template engine");
            }
            ImageTemplateEngine engine = factory.create(engineSettings, host);
            if (engine == null) {
                throw new IOException("Renderer component returned no image template engine");
            }
            return new LoadedRenderer(engine, classLoader);
        } catch (Throwable throwable) {
            try {
                classLoader.close();
            } catch (IOException closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            if (throwable instanceof IOException) {
                throw (IOException) throwable;
            }
            throw new IOException("Unable to load renderer component", throwable);
        }
    }

    private boolean isVerified(Path jar,
                               Path checksum,
                               Path signature,
                               String fileName,
                               String version) {
        try {
            verify(jar, checksum, signature, fileName, version);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void download(Path jar,
                          Path checksum,
                          Path signature,
                          String fileName,
                          String version) throws IOException {
        String jarUrl = componentUrl(version);
        byte[] checksumBytes = downloadBytes(jarUrl + ".sha256", MAX_CHECKSUM_BYTES);
        byte[] signatureBytes = downloadBytes(jarUrl + ".sig", MAX_SIGNATURE_BYTES);
        String expected = parseChecksum(checksumBytes, fileName);
        Path temporaryJar = jar.resolveSibling("." + fileName + "." + UUID.randomUUID() + ".tmp");
        Path temporaryChecksum = checksum.resolveSibling("." + checksum.getFileName() + ".tmp");
        Path temporarySignature = signature.resolveSibling("." + signature.getFileName() + ".tmp");
        try {
            String downloaded = downloadFile(jarUrl, temporaryJar, settings.getMaximumDownloadBytes());
            if (!expected.equals(downloaded)) {
                throw new IOException("Renderer component SHA-256 does not match its checksum");
            }
            verifySignature(temporaryJar, signatureBytes);
            validateJar(temporaryJar, version);
            Files.write(temporaryChecksum, checksumBytes,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.write(temporarySignature, signatureBytes,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveReplacing(temporaryJar, jar);
            moveReplacing(temporaryChecksum, checksum);
            moveReplacing(temporarySignature, signature);
        } finally {
            deleteQuietly(temporaryJar);
            deleteQuietly(temporaryChecksum);
            deleteQuietly(temporarySignature);
        }
    }

    private void verify(Path jar,
                        Path checksum,
                        Path signature,
                        String fileName,
                        String version) throws IOException {
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(checksum)
                || !Files.isRegularFile(signature)) {
            throw new IOException("Renderer component cache is incomplete");
        }
        if (Files.size(jar) <= 0L || Files.size(jar) > settings.getMaximumDownloadBytes()) {
            throw new IOException("Renderer component cache has an invalid size");
        }
        String expected = parseChecksum(readLimited(checksum, MAX_CHECKSUM_BYTES), fileName);
        String actual = sha256(jar);
        if (!expected.equals(actual)) {
            throw new IOException("Cached renderer component SHA-256 is invalid");
        }
        verifySignature(jar, readLimited(signature, MAX_SIGNATURE_BYTES));
        validateJar(jar, version);
    }

    private String componentVersion() throws IOException {
        String version = settings.getComponentVersion();
        if (version.isEmpty()) {
            version = platform.getPluginVersion();
        }
        version = normalizeVersion(version);
        if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IOException("Renderer component version is invalid: " + version);
        }
        return version;
    }

    private String componentUrl(String version) throws IOException {
        String configured = settings.getComponentDownloadUrl().replace("%version%", version);
        URL url = requireOfficialUrl(configured, true);
        return url.toString();
    }

    private String downloadFile(String value, Path target, long maximumBytes) throws IOException {
        MessageDigest digest = digest();
        long total = 0L;
        HttpURLConnection connection = open(value);
        try {
            long declared = connection.getContentLengthLong();
            if (declared > maximumBytes) {
                throw new IOException("Renderer component exceeds its configured download limit");
            }
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(target,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > maximumBytes) {
                        throw new IOException("Renderer component exceeds its configured download limit");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
        if (total == 0L) {
            throw new IOException("Downloaded renderer component is empty");
        }
        return hex(digest.digest());
    }

    private byte[] downloadBytes(String value, int maximumBytes) throws IOException {
        HttpURLConnection connection = open(value);
        try {
            if (connection.getContentLengthLong() > maximumBytes) {
                throw new IOException("Renderer metadata exceeds its download limit");
            }
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input, maximumBytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String value) throws IOException {
        URL current = requireOfficialUrl(value, true);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(settings.getDownloadConnectTimeoutMs());
            connection.setReadTimeout(settings.getDownloadReadTimeoutMs());
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "ShitBot-Renderer/" + platform.getPluginVersion());
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                return connection;
            }
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                connection.disconnect();
                throw new IOException("Renderer component request returned HTTP " + status);
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.trim().isEmpty()) {
                throw new IOException("Renderer component redirect has no Location header");
            }
            current = requireOfficialUrl(new URL(current, location).toString(), false);
        }
        throw new IOException("Renderer component request has too many redirects");
    }

    private URL requireOfficialUrl(String value, boolean initial) throws IOException {
        final URL url;
        try {
            url = new URL(value);
        } catch (Exception exception) {
            throw new IOException("Renderer component URL is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || url.getUserInfo() != null || url.getPort() != -1) {
            throw new IOException("Renderer component URL must use HTTPS without credentials or a port");
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if ("github.com".equals(host)
                && url.getPath().startsWith("/hutuyee/ShitBot/releases/download/")) {
            return url;
        }
        if (!initial && ("release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host))) {
            return url;
        }
        throw new IOException("Renderer component URL is outside the ShitBot GitHub Release");
    }

    private String parseChecksum(byte[] bytes, String fileName) throws IOException {
        Matcher matcher = CHECKSUM.matcher(new String(bytes, StandardCharsets.US_ASCII).trim());
        if (!matcher.matches()) {
            throw new IOException("Renderer checksum has an invalid format");
        }
        if (matcher.group(2) != null) {
            String named = matcher.group(2).trim().replace('\\', '/');
            int slash = named.lastIndexOf('/');
            named = slash < 0 ? named : named.substring(slash + 1);
            if (!fileName.equals(named)) {
                throw new IOException("Renderer checksum belongs to a different file");
            }
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    private void verifySignature(Path jar, byte[] signatureBytes) throws IOException {
        if (signatureBytes.length == 0 || signatureBytes.length > MAX_SIGNATURE_BYTES) {
            throw new IOException("Renderer detached signature has an invalid size");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(readPublicKey());
            try (InputStream input = Files.newInputStream(jar)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) verifier.update(buffer, 0, read);
                }
            }
            if (!verifier.verify(signatureBytes)) {
                throw new IOException("Renderer detached signature is invalid");
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to verify renderer detached signature", exception);
        }
    }

    private PublicKey readPublicKey() throws IOException {
        byte[] pem;
        try (InputStream input = RendererComponentLoader.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (input == null) throw new IOException("Renderer signing public key is missing");
            pem = readLimited(input, MAX_PUBLIC_KEY_BYTES);
        }
        String encoded = new String(pem, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Renderer signing public key is malformed", exception);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Renderer signing public key is invalid", exception);
        }
    }

    private void validateJar(Path jar, String expectedVersion) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry service = zip.getEntry(SERVICE_ENTRY);
            ZipEntry version = zip.getEntry("META-INF/shitbot-renderer.version");
            if (service == null || service.isDirectory() || version == null || version.isDirectory()) {
                throw new IOException("Renderer component is missing required metadata");
            }
            byte[] bytes;
            try (InputStream input = zip.getInputStream(version)) {
                bytes = readLimited(input, 256);
            }
            String embedded = normalizeVersion(new String(bytes, StandardCharsets.UTF_8));
            if (!normalizeVersion(expectedVersion).equalsIgnoreCase(embedded)) {
                throw new IOException("Renderer component version does not match the plugin version");
            }
        }
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(String.format(Locale.ROOT, "%02x", current & 0xff));
        }
        return value.toString();
    }

    private byte[] readLimited(Path path, int maximumBytes) throws IOException {
        if (Files.size(path) > maximumBytes) throw new IOException("Renderer metadata is too large");
        try (InputStream input = Files.newInputStream(path)) {
            return readLimited(input, maximumBytes);
        }
    }

    private byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > maximumBytes) throw new IOException("Renderer metadata is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String normalizeVersion(String value) {
        String version = value == null ? "" : value.trim();
        return version.startsWith("v") || version.startsWith("V")
                ? version.substring(1).trim() : version;
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

    static final class LoadedRenderer implements AutoCloseable {
        private final ImageTemplateEngine engine;
        private final URLClassLoader classLoader;

        private LoadedRenderer(ImageTemplateEngine engine, URLClassLoader classLoader) {
            this.engine = engine;
            this.classLoader = classLoader;
        }

        ImageTemplateEngine getEngine() {
            return engine;
        }

        @Override
        public void close() {
            try {
                engine.close();
            } finally {
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
