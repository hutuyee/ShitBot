package haaa.shitbot.core.console;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/** Builds explicitly configured TLS sockets for the proxy-to-backend console channel. */
public final class ConsoleTlsSupport {
    private ConsoleTlsSupport() {
    }

    public static SSLSocket createClientSocket(ConsoleSettings.BackendEndpoint endpoint,
                                               Path dataDirectory) throws IOException {
        if (endpoint == null || !endpoint.isTlsEnabled()) {
            throw new IOException("Console endpoint TLS is not enabled");
        }
        try {
            KeyManager[] keyManagers = endpoint.getTlsKeyStore().isEmpty()
                    ? null
                    : keyManagers(resolve(dataDirectory, endpoint.getTlsKeyStore()),
                    endpoint.getTlsKeyStorePassword());
            TrustManager[] trustManagers = trustManagers(
                    endpoint.getTlsTrustStore().isEmpty()
                            ? null
                            : resolve(dataDirectory, endpoint.getTlsTrustStore()),
                    endpoint.getTlsTrustStorePassword());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, null);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            return socket;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to initialize console client TLS", exception);
        }
    }

    public static SSLServerSocket createServerSocket(ConsoleSettings.BackendListener listener,
                                                      Path dataDirectory) throws IOException {
        if (listener == null || !listener.isTlsEnabled()) {
            throw new IOException("Console listener TLS is not enabled");
        }
        if (listener.getTlsKeyStore().isEmpty()) {
            throw new IOException("Console listener TLS key-store is required");
        }
        if (listener.isTlsRequireClientCertificate() && listener.getTlsTrustStore().isEmpty()) {
            throw new IOException("Console listener mTLS requires a trust-store");
        }
        try {
            KeyManager[] keyManagers = keyManagers(
                    resolve(dataDirectory, listener.getTlsKeyStore()),
                    listener.getTlsKeyStorePassword());
            TrustManager[] trustManagers = listener.getTlsTrustStore().isEmpty()
                    ? null
                    : trustManagers(resolve(dataDirectory, listener.getTlsTrustStore()),
                    listener.getTlsTrustStorePassword());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, null);
            SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            socket.setNeedClientAuth(listener.isTlsRequireClientCertificate());
            return socket;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to initialize console listener TLS", exception);
        }
    }

    private static KeyManager[] keyManagers(Path path, String password)
            throws IOException, GeneralSecurityException {
        char[] secret = chars(password);
        KeyStore keyStore = loadStore(path, secret);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, secret);
        return factory.getKeyManagers();
    }

    private static TrustManager[] trustManagers(Path path, String password)
            throws IOException, GeneralSecurityException {
        KeyStore trustStore = path == null ? null : loadStore(path, chars(password));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory.getTrustManagers();
    }

    private static KeyStore loadStore(Path path, char[] password)
            throws IOException, GeneralSecurityException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Console TLS store does not exist: " + path);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private static Path resolve(Path dataDirectory, String configured) throws IOException {
        Path path = Paths.get(configured);
        if (!path.isAbsolute()) {
            if (dataDirectory == null) {
                throw new IOException("Relative console TLS path requires a plugin data directory");
            }
            path = dataDirectory.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static char[] chars(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }
}
