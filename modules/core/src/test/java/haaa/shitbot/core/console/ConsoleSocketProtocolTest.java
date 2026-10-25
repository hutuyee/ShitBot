package haaa.shitbot.core.console;

import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.*;

/** Exercises the wire protocol entirely in memory; no listening sockets or remote endpoints. */
public class ConsoleSocketProtocolTest {
    private static final String TOKEN = "test-only-console-token-0123456789";
    private static final byte[] CHALLENGE = sequence(32);
    private static final byte[] NONCE = sequence(16);

    @Test
    public void acceptsSignedVersionTwoRequestsAndProtectsTheNonce() throws Exception {
        long timestamp = System.currentTimeMillis();
        ConsoleSocketProtocol.AuthenticatedRequest authenticated = read(packet(timestamp), TOKEN, CHALLENGE);
        assertEquals("request-1", authenticated.getRequest().getRequestId());
        assertEquals("list", authenticated.getRequest().getCommand());
        assertEquals(timestamp, authenticated.getTimestamp());
        assertArrayEquals(NONCE, authenticated.getNonce());
        assertEquals(Base64.getEncoder().encodeToString(NONCE), authenticated.getNonceKey());
        authenticated.getNonce()[0] = 99;
        assertArrayEquals(NONCE, authenticated.getNonce());
    }

    @Test
    public void rejectsTheWrongTokenAndAChallengeFromAnotherConnection() throws Exception {
        byte[] packet = packet(System.currentTimeMillis());
        IOException tokenError = assertThrows(IOException.class, () -> read(packet, TOKEN + "wrong", CHALLENGE));
        assertEquals("Console socket authentication failed", tokenError.getMessage());
        byte[] otherChallenge = CHALLENGE.clone();
        otherChallenge[0] ^= 1;
        IOException challengeError = assertThrows(IOException.class, () -> read(packet, TOKEN, otherChallenge));
        assertEquals("Console socket authentication failed", challengeError.getMessage());
    }

    @Test
    public void rejectsTamperedPayloadsAndSignaturesBeforeDecoding() throws Exception {
        byte[] tamperedPayload = packet(System.currentTimeMillis());
        tamperedPayload[33] ^= 1;
        IOException payloadError = assertThrows(IOException.class, () -> read(tamperedPayload, TOKEN, CHALLENGE));
        assertEquals("Console socket authentication failed", payloadError.getMessage());
        byte[] tamperedSignature = packet(System.currentTimeMillis());
        tamperedSignature[tamperedSignature.length - 1] ^= 1;
        assertEquals("Console socket authentication failed",
                assertThrows(IOException.class, () -> read(tamperedSignature, TOKEN, CHALLENGE)).getMessage());
    }

    @Test
    public void rejectsExpiredAndFutureTimestamps() throws Exception {
        for (long offset : new long[] {-120000L, 120000L}) {
            byte[] packet = packet(System.currentTimeMillis() + offset);
            IOException error = assertThrows(IOException.class, () -> read(packet, TOKEN, CHALLENGE));
            assertTrue(error.getMessage().contains("outside the allowed window"));
        }
    }

    @Test
    public void rejectsInvalidMagicVersionAndTruncatedFrames() throws Exception {
        byte[] valid = packet(System.currentTimeMillis());
        for (int offset : new int[] {0, 4}) {
            byte[] packet = valid.clone();
            packet[offset] = 0;
            assertEquals("Unsupported console socket request",
                    assertThrows(IOException.class, () -> read(packet, TOKEN, CHALLENGE)).getMessage());
        }
        assertThrows(IOException.class, () -> read(Arrays.copyOf(valid, valid.length - 1), TOKEN, CHALLENGE));
    }

    @Test
    public void rejectsInvalidPayloadLengthsBeforeAllocating() throws Exception {
        for (int length : new int[] {-1, 0, 32768, Integer.MAX_VALUE}) {
            byte[] packet = packet(System.currentTimeMillis());
            ByteBuffer.wrap(packet).putInt(29, length);
            assertEquals("Invalid console socket payload length",
                    assertThrows(IOException.class, () -> read(packet, TOKEN, CHALLENGE)).getMessage());
        }
    }

    @Test
    public void rejectsMissingOrShortCredentialsAndInvalidChallenges() {
        for (String token : new String[] {null, "", "short", "                "}) {
            assertThrows(IOException.class, () -> read(new byte[0], token, CHALLENGE));
        }
        for (byte[] challenge : new byte[][] {null, new byte[31], new byte[33]}) {
            assertEquals("Invalid console socket challenge",
                    assertThrows(IOException.class, () -> read(new byte[0], TOKEN, challenge)).getMessage());
        }
    }

    @Test
    public void challengeFrameCarriesTheReturnedRandomBytes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] challenge = ConsoleSocketProtocol.writeChallenge(bytes);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(0x53425443, input.readInt());
        assertEquals(2, input.readUnsignedByte());
        byte[] actual = new byte[32];
        input.readFully(actual);
        assertArrayEquals(challenge, actual);
        assertEquals(-1, input.read());
    }

    @Test
    public void responseSignatureBindsThePayloadChallengeAndNonce() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleResult result = new ConsoleResult("request-1", ConsoleResult.Status.SUCCESS, "玩家在线", "survival");
        ConsoleSocketProtocol.writeResult(bytes, TOKEN, CHALLENGE, NONCE, result);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(0x53425452, input.readInt());
        assertEquals(2, input.readUnsignedByte());
        byte[] payload = new byte[input.readInt()];
        input.readFully(payload);
        byte[] signature = new byte[32];
        input.readFully(signature);
        byte[] signed = ByteBuffer.allocate(1 + 32 + 16 + payload.length)
                .put((byte) 'R').put(CHALLENGE).put(NONCE).put(payload).array();
        assertArrayEquals(hmac(signed), signature);
        assertEquals("玩家在线", ConsoleMessageCodec.decodeResult(payload).getOutput());
        assertEquals(-1, input.read());
    }

    @Test
    public void responsesRejectInvalidNoncesAndOversizedPayloads() {
        ConsoleResult result = new ConsoleResult("id", ConsoleResult.Status.SUCCESS, "ok", "server");
        for (byte[] nonce : new byte[][] {null, new byte[15], new byte[17]}) {
            assertEquals("Invalid response nonce", assertThrows(IOException.class,
                    () -> ConsoleSocketProtocol.writeResult(new ByteArrayOutputStream(), TOKEN, CHALLENGE, nonce, result)).getMessage());
        }
        char[] text = new char[16000];
        Arrays.fill(text, '中');
        ConsoleResult oversized = new ConsoleResult("id", ConsoleResult.Status.SUCCESS, new String(text), "server");
        assertEquals("Console socket payload is too large", assertThrows(IOException.class,
                () -> ConsoleSocketProtocol.writeResult(new ByteArrayOutputStream(), TOKEN, CHALLENGE, NONCE, oversized)).getMessage());
    }

    private static ConsoleSocketProtocol.AuthenticatedRequest read(byte[] packet, String token, byte[] challenge) throws IOException {
        return ConsoleSocketProtocol.readRequest(new ByteArrayInputStream(packet), token, challenge);
    }

    private static byte[] packet(long timestamp) throws Exception {
        ConsoleRequest request = new ConsoleRequest("request-1", ConsoleRequest.Operation.COMMAND,
                ConsoleSettings.Target.BACKEND, "list", "", null, "survival", 5, 15);
        byte[] payload = ConsoleMessageCodec.encodeRequest(request);
        byte[] signed = ByteBuffer.allocate(1 + 32 + 8 + 16 + payload.length)
                .put((byte) 'Q').put(CHALLENGE).putLong(timestamp).put(NONCE).put(payload).array();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x53425451);
        output.writeByte(2);
        output.writeLong(timestamp);
        output.write(NONCE);
        output.writeInt(payload.length);
        output.write(payload);
        output.write(hmac(signed));
        return bytes.toByteArray();
    }

    private static byte[] hmac(byte[] signed) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signed);
    }

    private static byte[] sequence(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) bytes[index] = (byte) index;
        return bytes;
    }
}
