package haaa.shitbot.renderer;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** Pins each HTTPS connection to its checked address while retaining the original TLS hostname. */
final class PinnedHttpsSocketFactory extends SSLSocketFactory {
    private static final int PORT = 443;
    private final SSLSocketFactory delegate;
    private final String host;
    private final InetAddress address;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final long deadline;

    PinnedHttpsSocketFactory(SSLSocketFactory delegate, String host, InetAddress address,
                            int connectTimeoutMillis, int readTimeoutMillis, long deadline) {
        this.delegate = delegate;
        this.host = host;
        this.address = address;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.deadline = deadline;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() {
        // HttpsURLConnection connects this transport, then calls the layered
        // createSocket overload below before any HTTP request is sent.
        return new Socket(Proxy.NO_PROXY) {
            private InputStream boundedInput;

            @Override
            public void connect(SocketAddress endpoint) throws IOException {
                connect(endpoint, connectTimeoutMillis);
            }

            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                if (!(endpoint instanceof InetSocketAddress)
                        || ((InetSocketAddress) endpoint).getPort() != PORT) {
                    throw new IOException("Unexpected remote image connection endpoint");
                }
                // Ignore any subsequent DNS result supplied by the URL handler.
                super.connect(new InetSocketAddress(address, PORT),
                        remainingTimeout(timeout <= 0 ? connectTimeoutMillis
                                : Math.min(timeout, connectTimeoutMillis)));
            }

            @Override
            public InputStream getInputStream() throws IOException {
                if (boundedInput == null) {
                    boundedInput = new FilterInputStream(super.getInputStream()) {
                        @Override
                        public int read() throws IOException {
                            setSoTimeout(remainingTimeout(readTimeoutMillis));
                            return in.read();
                        }

                        @Override
                        public int read(byte[] bytes, int offset, int length) throws IOException {
                            setSoTimeout(remainingTimeout(readTimeoutMillis));
                            return in.read(bytes, offset, length);
                        }
                    };
                }
                return boundedInput;
            }
        };
    }

    @Override
    public Socket createSocket(Socket socket, String requestedHost, int port, boolean autoClose)
            throws IOException {
        requireHost(requestedHost, port);
        if (!socket.isConnected() || !address.equals(socket.getInetAddress()) || socket.getPort() != PORT) {
            throw new IOException("Remote image connection address does not match its validated address");
        }
        SSLSocket secured = (SSLSocket) delegate.createSocket(socket, host, PORT, autoClose);
        SSLParameters parameters = secured.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        secured.setSSLParameters(parameters);
        return secured;
    }

    @Override
    public Socket createSocket(String requestedHost, int port) throws IOException {
        requireHost(requestedHost, port);
        return connect(null, 0);
    }

    @Override
    public Socket createSocket(String requestedHost, int port, InetAddress localAddress, int localPort)
            throws IOException {
        requireHost(requestedHost, port);
        return connect(localAddress, localPort);
    }

    @Override
    public Socket createSocket(InetAddress requestedAddress, int port) throws IOException {
        requireAddress(requestedAddress, port);
        return connect(null, 0);
    }

    @Override
    public Socket createSocket(InetAddress requestedAddress, int port, InetAddress localAddress, int localPort)
            throws IOException {
        requireAddress(requestedAddress, port);
        return connect(localAddress, localPort);
    }

    private Socket connect(InetAddress localAddress, int localPort) throws IOException {
        Socket socket = createSocket();
        try {
            if (localAddress != null || localPort != 0) {
                socket.bind(new InetSocketAddress(localAddress, localPort));
            }
            socket.connect(new InetSocketAddress(address, PORT), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            return createSocket(socket, host, PORT, true);
        } catch (IOException | RuntimeException exception) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private void requireHost(String requestedHost, int port) throws IOException {
        if (!host.equalsIgnoreCase(requestedHost) || port != PORT) {
            throw new IOException("Unexpected remote image TLS endpoint");
        }
    }

    private void requireAddress(InetAddress requestedAddress, int port) throws IOException {
        if (!address.equals(requestedAddress) || port != PORT) {
            throw new IOException("Unexpected remote image TLS address");
        }
    }

    private int remainingTimeout(int maximum) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (Thread.currentThread().isInterrupted() || remaining <= 0L) {
            throw new SocketTimeoutException("Remote image download deadline expired");
        }
        return (int) Math.min(maximum, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
}
