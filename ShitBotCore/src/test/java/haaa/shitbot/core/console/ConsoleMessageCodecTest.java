package haaa.shitbot.core.console;

import haaa.shitbot.core.update.BackendUpdatePayload;
import haaa.shitbot.core.update.ReleaseAsset;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class ConsoleMessageCodecTest {
    @Test
    public void commandRoundTripPreservesRoutingAndPermissions() throws Exception {
        ConsoleRequest request = new ConsoleRequest("request-1", ConsoleRequest.Operation.COMMAND,
                ConsoleSettings.Target.PROXY, "say 你好😀", "shitbot.admin", Arrays.asList("Steve", "Alex"),
                "survival", 10, 45);
        ConsoleRequest decoded = ConsoleMessageCodec.decodeRequest(ConsoleMessageCodec.encodeRequest(request));
        assertEquals("request-1", decoded.getRequestId());
        assertEquals(ConsoleRequest.Operation.COMMAND, decoded.getOperation());
        assertEquals(ConsoleSettings.Target.PROXY, decoded.getTarget());
        assertEquals("say 你好😀", decoded.getCommand());
        assertEquals("shitbot.admin", decoded.getPermission());
        assertEquals(Arrays.asList("Steve", "Alex"), decoded.getPlayerNames());
        assertEquals("survival", decoded.getServer());
        assertEquals(10, decoded.getCaptureSeconds());
        assertEquals(45, decoded.getTimeoutSeconds());
        assertNull(decoded.getUpdatePayload());
    }

    @Test
    public void placeholderRequestsRetainTheirOperationAndValues() throws Exception {
        ConsoleRequest request = ConsoleRequest.placeholders("Steve",
                Arrays.asList("%player_name%", "%server_tps%"), "survival", 20);
        ConsoleRequest decoded = ConsoleMessageCodec.decodeRequest(ConsoleMessageCodec.encodeRequest(request));
        assertEquals(ConsoleRequest.Operation.PLACEHOLDERS, decoded.getOperation());
        assertEquals(Collections.singletonList("Steve"), decoded.getPlayerNames());
        assertEquals(request.getPlaceholders(), decoded.getPlaceholders());
    }

    @Test
    public void updateRoundTripPreservesAllSignedAssetMetadata() throws Exception {
        ReleaseAsset jar = new ReleaseAsset("plugin.jar", "https://example.invalid/plugin.jar", 1234L, "sha256:1234");
        ReleaseAsset checksum = new ReleaseAsset("plugin.jar.sha256", "https://example.invalid/checksum", 64L, "sha256:5678");
        ReleaseAsset signature = new ReleaseAsset("plugin.jar.sig", "https://example.invalid/signature", 256L, "sha256:9012");
        BackendUpdatePayload payload = new BackendUpdatePayload("1.2.3", "https://example.invalid/release", jar, checksum, signature);
        ConsoleRequest request = new ConsoleRequest("update-1", ConsoleRequest.Operation.UPDATE,
                ConsoleSettings.Target.BACKEND, "", "", null, "survival", 1, 60, payload);
        BackendUpdatePayload decoded = ConsoleMessageCodec.decodeRequest(
                ConsoleMessageCodec.encodeRequest(request)).getUpdatePayload();
        assertEquals("1.2.3", decoded.getLatestVersion());
        assertEquals("https://example.invalid/release", decoded.getReleaseUrl());
        assertTrue(jar.isSameAsset(decoded.getJarAsset()));
        assertTrue(checksum.isSameAsset(decoded.getChecksumAsset()));
        assertTrue(signature.isSameAsset(decoded.getSignatureAsset()));
    }

    @Test
    public void everyResultStatusRoundTripsWithMultilineOutput() throws Exception {
        for (ConsoleResult.Status status : ConsoleResult.Status.values()) {
            ConsoleResult result = new ConsoleResult("result-1", status, "第一行\nsecond line\u0000", "survival");
            ConsoleResult decoded = ConsoleMessageCodec.decodeResult(ConsoleMessageCodec.encodeResult(result));
            assertEquals("result-1", decoded.getRequestId());
            assertEquals(status, decoded.getStatus());
            assertEquals(result.getOutput(), decoded.getOutput());
            assertEquals("survival", decoded.getSource());
        }
    }

    @Test
    public void decodesAFixedVersionSixResponse() throws Exception {
        // Fixed wire fixture: SBC1, version 6, response, id "id", success, "ok", "s".
        byte[] fixture = {0x53, 0x42, 0x43, 0x31, 6, 2, 0, 2, 'i', 'd', 0, 0, 2, 'o', 'k', 0, 1, 's'};
        ConsoleResult decoded = ConsoleMessageCodec.decodeResult(fixture);
        assertEquals("id", decoded.getRequestId());
        assertEquals(ConsoleResult.Status.SUCCESS, decoded.getStatus());
        assertEquals("ok", decoded.getOutput());
        assertEquals("s", decoded.getSource());
        assertArrayEquals(fixture, ConsoleMessageCodec.encodeResult(decoded));
    }

    @Test
    public void rejectsInvalidHeadersTypesAndLengths() throws Exception {
        byte[] valid = ConsoleMessageCodec.encodeRequest(request());
        for (int offset : new int[] {0, 4, 5}) {
            byte[] broken = valid.clone();
            broken[offset] = (byte) 0x7f;
            assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeRequest(broken));
        }
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeRequest(null));
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeRequest(new byte[6]));
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeRequest(new byte[65536]));
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeResult(valid));
    }

    @Test
    public void rejectsTruncationAndTrailingBytesInBothDirections() throws Exception {
        byte[] request = ConsoleMessageCodec.encodeRequest(request());
        byte[] result = ConsoleMessageCodec.encodeResult(new ConsoleResult("id", ConsoleResult.Status.SUCCESS, "ok", "server"));
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeRequest(Arrays.copyOf(request, request.length - 1)));
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeRequest(Arrays.copyOf(request, request.length + 1)));
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeResult(Arrays.copyOf(result, result.length - 1)));
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeResult(Arrays.copyOf(result, result.length + 1)));
    }

    @Test
    public void rejectsInvalidEnumOrdinals() throws Exception {
        byte[] request = ConsoleMessageCodec.encodeRequest(request());
        // The fixture request id is "id": operation and target follow its UTF length and two bytes.
        for (int offset : new int[] {10, 11}) {
            byte[] broken = request.clone();
            broken[offset] = (byte) 255;
            assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeRequest(broken));
        }
        byte[] result = ConsoleMessageCodec.encodeResult(new ConsoleResult("id", ConsoleResult.Status.SUCCESS, "ok", "s"));
        result[10] = (byte) 255;
        assertThrows(IOException.class, () -> ConsoleMessageCodec.decodeResult(result));
    }

    @Test
    public void rejectsNegativeAndExcessiveCollectionCounts() {
        for (int count : new int[] {-1, 33, Integer.MAX_VALUE}) {
            assertEquals("Invalid player count", assertThrows(IOException.class,
                    () -> ConsoleMessageCodec.decodeRequest(countFixture(count, 0))).getMessage());
        }
        for (int count : new int[] {-1, 65, Integer.MAX_VALUE}) {
            assertEquals("Invalid placeholder count", assertThrows(IOException.class,
                    () -> ConsoleMessageCodec.decodeRequest(countFixture(0, count))).getMessage());
        }
    }

    @Test
    public void encodingBoundsCollectionsAndText() throws Exception {
        ConsoleRequest request = new ConsoleRequest("id", ConsoleRequest.Operation.PLACEHOLDERS,
                ConsoleSettings.Target.BACKEND, "", "", Collections.nCopies(33, "Steve"), "server", 1, 15,
                null, Collections.nCopies(65, "%player_name%"));
        ConsoleRequest decoded = ConsoleMessageCodec.decodeRequest(ConsoleMessageCodec.encodeRequest(request));
        assertEquals(32, decoded.getPlayerNames().size());
        assertEquals(64, decoded.getPlaceholders().size());
        String text = String.join("", Collections.nCopies(16001, "x"));
        ConsoleResult result = new ConsoleResult("id", ConsoleResult.Status.SUCCESS, text, "server");
        assertEquals(text.substring(0, 16000), ConsoleMessageCodec.decodeResult(
                ConsoleMessageCodec.encodeResult(result)).getOutput());
    }

    private static ConsoleRequest request() {
        return new ConsoleRequest("id", ConsoleRequest.Operation.COMMAND, ConsoleSettings.Target.BACKEND,
                "list", "", Collections.singletonList("Steve"), "server", 5, 15);
    }

    private static byte[] countFixture(int players, int placeholders) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x53424331);
        output.writeByte(6);
        output.writeByte(1);
        output.writeUTF("id");
        output.writeByte(0);
        output.writeByte(0);
        output.writeUTF("list");
        output.writeUTF("");
        output.writeUTF("server");
        output.writeInt(5);
        output.writeInt(15);
        output.writeInt(players);
        output.writeInt(placeholders);
        output.writeBoolean(false);
        return bytes.toByteArray();
    }
}
