package haaa.shitbot.core.console;

import haaa.shitbot.core.util.NetworkUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public final class ConsoleSocketProtocol {
    private static final int REQUEST_MAGIC = 0x53425451;
    private static final int RESPONSE_MAGIC = 0x53425452;
    private static final int CHALLENGE_MAGIC = 0x53425443;
    private static final int VERSION = 2;
    private static final int NONCE_LENGTH = 16;
    private static final int CHALLENGE_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 32;
    private static final int MAX_PAYLOAD = 32767;
    private static final long MAX_CLOCK_SKEW_MILLIS = 30000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ConsoleSocketProtocol() {
    }

    public static ConsoleResult exchange(ConsoleSettings.BackendEndpoint endpoint,
                                         ConsoleSettings.BackendTransport transport,
                                         ConsoleRequest request) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(request.getTimeoutSeconds());
        return exchange(endpoint, transport, request, null, deadline);
    }

    public static ConsoleResult exchange(ConsoleSettings.BackendEndpoint endpoint,
                                         ConsoleSettings.BackendTransport transport,
                                         ConsoleRequest request,
                                         Path dataDirectory,
                                         long deadlineNanos) throws IOException {
        requireToken(endpoint.getToken());
        if (!endpoint.isTlsEnabled()
                && !NetworkUtil.isLoopbackHost(endpoint.getHost())
                && !endpoint.isAllowInsecureRemotePlaintext()) {
            throw new IOException("Remote console endpoint requires TLS; plaintext was not explicitly allowed");
        }
        Socket socket = endpoint.isTlsEnabled()
                ? ConsoleTlsSupport.createClientSocket(endpoint, dataDirectory)
                : new Socket();
        try {
            socket.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()),
                    remainingTimeoutMillis(deadlineNanos, transport.getConnectTimeoutMillis()));
            socket.setSoTimeout(remainingTimeoutMillis(
                    deadlineNanos, transport.getReadTimeoutSeconds() * 1000));
            if (socket instanceof javax.net.ssl.SSLSocket) {
                ((javax.net.ssl.SSLSocket) socket).startHandshake();
                socket.setSoTimeout(remainingTimeoutMillis(
                        deadlineNanos, transport.getReadTimeoutSeconds() * 1000));
            }
            byte[] challenge = readChallenge(socket.getInputStream());
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            writeRequest(socket.getOutputStream(), endpoint.getToken(), challenge, nonce, request);
            socket.setSoTimeout(remainingTimeoutMillis(
                    deadlineNanos, transport.getReadTimeoutSeconds() * 1000));
            ConsoleResult result = readResult(
                    socket.getInputStream(), endpoint.getToken(), challenge, nonce);
            if (!request.getRequestId().equals(result.getRequestId())) {
                throw new IOException("Console socket response request ID does not match");
            }
            if (!endpoint.getName().equalsIgnoreCase(result.getSource())) {
                throw new IOException("Console socket response server does not match target endpoint");
            }
            return new ConsoleResult(result.getRequestId(), result.getStatus(),
                    result.getOutput(), endpoint.getName());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static byte[] writeChallenge(OutputStream rawOutput) throws IOException {
        byte[] challenge = new byte[CHALLENGE_LENGTH];
        RANDOM.nextBytes(challenge);
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(rawOutput));
        output.writeInt(CHALLENGE_MAGIC);
        output.writeByte(VERSION);
        output.write(challenge);
        output.flush();
        return challenge;
    }

    private static byte[] readChallenge(InputStream rawInput) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput));
        if (input.readInt() != CHALLENGE_MAGIC || input.readUnsignedByte() != VERSION) {
            throw new IOException("Unsupported console socket challenge");
        }
        byte[] challenge = new byte[CHALLENGE_LENGTH];
        input.readFully(challenge);
        return challenge;
    }

    public static AuthenticatedRequest readRequest(InputStream rawInput,
                                                   String token,
                                                   byte[] challenge) throws IOException {
        requireToken(token);
        requireChallenge(challenge);
        DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput));
        if (input.readInt() != REQUEST_MAGIC || input.readUnsignedByte() != VERSION) {
            throw new IOException("Unsupported console socket request");
        }
        long timestamp = input.readLong();
        if (Math.abs(System.currentTimeMillis() - timestamp) > MAX_CLOCK_SKEW_MILLIS) {
            throw new IOException("Console socket request timestamp is outside the allowed window");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        input.readFully(nonce);
        byte[] payload = readPayload(input);
        byte[] signature = new byte[SIGNATURE_LENGTH];
        input.readFully(signature);
        byte[] expected = sign(token, requestSigningBytes(challenge, timestamp, nonce, payload));
        if (!MessageDigest.isEqual(signature, expected)) {
            throw new IOException("Console socket authentication failed");
        }
        return new AuthenticatedRequest(
                ConsoleMessageCodec.decodeRequest(payload), nonce, timestamp,
                Base64.getEncoder().encodeToString(nonce));
    }

    public static void writeResult(OutputStream rawOutput,
                                   String token,
                                   byte[] challenge,
                                   byte[] nonce,
                                   ConsoleResult result) throws IOException {
        requireToken(token);
        requireChallenge(challenge);
        if (nonce == null || nonce.length != NONCE_LENGTH) {
            throw new IOException("Invalid response nonce");
        }
        byte[] payload = ConsoleMessageCodec.encodeResult(result);
        ensurePayload(payload);
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(rawOutput));
        output.writeInt(RESPONSE_MAGIC);
        output.writeByte(VERSION);
        output.writeInt(payload.length);
        output.write(payload);
        output.write(sign(token, responseSigningBytes(challenge, nonce, payload)));
        output.flush();
    }

    private static void writeRequest(OutputStream rawOutput,
                                     String token,
                                     byte[] challenge,
                                     byte[] nonce,
                                     ConsoleRequest request) throws IOException {
        requireChallenge(challenge);
        byte[] payload = ConsoleMessageCodec.encodeRequest(request);
        ensurePayload(payload);
        long timestamp = System.currentTimeMillis();
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(rawOutput));
        output.writeInt(REQUEST_MAGIC);
        output.writeByte(VERSION);
        output.writeLong(timestamp);
        output.write(nonce);
        output.writeInt(payload.length);
        output.write(payload);
        output.write(sign(token, requestSigningBytes(challenge, timestamp, nonce, payload)));
        output.flush();
    }

    private static ConsoleResult readResult(InputStream rawInput,
                                            String token,
                                            byte[] challenge,
                                            byte[] nonce) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput));
        if (input.readInt() != RESPONSE_MAGIC || input.readUnsignedByte() != VERSION) {
            throw new IOException("Unsupported console socket response");
        }
        byte[] payload = readPayload(input);
        byte[] signature = new byte[SIGNATURE_LENGTH];
        input.readFully(signature);
        byte[] expected = sign(token, responseSigningBytes(challenge, nonce, payload));
        if (!MessageDigest.isEqual(signature, expected)) {
            throw new IOException("Console socket response authentication failed");
        }
        return ConsoleMessageCodec.decodeResult(payload);
    }

    private static byte[] readPayload(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_PAYLOAD) {
            throw new IOException("Invalid console socket payload length");
        }
        byte[] payload = new byte[length];
        input.readFully(payload);
        return payload;
    }

    private static byte[] requestSigningBytes(byte[] challenge,
                                              long timestamp,
                                              byte[] nonce,
                                              byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte('Q');
        output.write(challenge);
        output.writeLong(timestamp);
        output.write(nonce);
        output.write(payload);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] responseSigningBytes(byte[] challenge,
                                               byte[] nonce,
                                               byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte('R');
        output.write(challenge);
        output.write(nonce);
        output.write(payload);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] sign(String token, byte[] data) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to calculate console socket signature", exception);
        }
    }

    private static void ensurePayload(byte[] payload) throws IOException {
        if (payload == null || payload.length <= 0 || payload.length > MAX_PAYLOAD) {
            throw new IOException("Console socket payload is too large");
        }
    }

    private static void requireToken(String token) throws IOException {
        if (token == null || token.trim().length() < 16) {
            throw new IOException("Console socket token must contain at least 16 characters");
        }
    }

    private static void requireChallenge(byte[] challenge) throws IOException {
        if (challenge == null || challenge.length != CHALLENGE_LENGTH) {
            throw new IOException("Invalid console socket challenge");
        }
    }

    private static int remainingTimeoutMillis(long deadlineNanos, int configuredMaximum)
            throws SocketTimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new SocketTimeoutException("Console socket request deadline expired");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        return (int) Math.min(Math.max(1, configuredMaximum), remainingMillis);
    }

    public static final class AuthenticatedRequest {
        private final ConsoleRequest request;
        private final byte[] nonce;
        private final long timestamp;
        private final String nonceKey;

        private AuthenticatedRequest(ConsoleRequest request, byte[] nonce, long timestamp, String nonceKey) {
            this.request = request;
            this.nonce = nonce.clone();
            this.timestamp = timestamp;
            this.nonceKey = nonceKey;
        }

        public ConsoleRequest getRequest() { return request; }
        public byte[] getNonce() { return nonce.clone(); }
        public long getTimestamp() { return timestamp; }
        public String getNonceKey() { return nonceKey; }
    }
}
