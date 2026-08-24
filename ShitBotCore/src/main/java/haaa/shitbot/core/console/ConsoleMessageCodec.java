package haaa.shitbot.core.console;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ConsoleMessageCodec {
    public static final String CHANNEL = "shitbot:console";
    private static final int MAGIC = 0x53424331;
    private static final int VERSION = 1;
    private static final int TYPE_REQUEST = 1;
    private static final int TYPE_RESPONSE = 2;
    private static final int MAX_PLAYERS = 32;
    private static final int MAX_TEXT = 4000;

    private ConsoleMessageCodec() {
    }

    public static byte[] encodeRequest(ConsoleRequest request) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        output.writeByte(TYPE_REQUEST);
        writeText(output, request.getRequestId());
        output.writeByte(request.getOperation().ordinal());
        output.writeByte(request.getTarget().ordinal());
        writeText(output, request.getCommand());
        writeText(output, request.getPermission());
        writeText(output, request.getServer());
        output.writeInt(request.getCaptureSeconds());
        output.writeInt(request.getTimeoutSeconds());
        int count = Math.min(request.getPlayerNames().size(), MAX_PLAYERS);
        output.writeInt(count);
        for (int index = 0; index < count; index++) {
            writeText(output, request.getPlayerNames().get(index));
        }
        output.flush();
        return bytes.toByteArray();
    }

    public static ConsoleRequest decodeRequest(byte[] data) throws IOException {
        DataInputStream input = open(data, TYPE_REQUEST);
        String requestId = readText(input);
        ConsoleRequest.Operation operation = enumValue(
                ConsoleRequest.Operation.values(), input.readUnsignedByte(), "operation");
        ConsoleSettings.Target target = enumValue(
                ConsoleSettings.Target.values(), input.readUnsignedByte(), "target");
        String command = readText(input);
        String permission = readText(input);
        String server = readText(input);
        int captureSeconds = input.readInt();
        int timeoutSeconds = input.readInt();
        int playerCount = input.readInt();
        if (playerCount < 0 || playerCount > MAX_PLAYERS) {
            throw new IOException("Invalid player count");
        }
        List<String> playerNames = new ArrayList<String>(playerCount);
        for (int index = 0; index < playerCount; index++) {
            playerNames.add(readText(input));
        }
        return new ConsoleRequest(requestId, operation, target, command, permission,
                playerNames, server, captureSeconds, timeoutSeconds);
    }

    public static byte[] encodeResult(ConsoleResult result) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        output.writeByte(TYPE_RESPONSE);
        writeText(output, result.getRequestId());
        output.writeByte(result.getStatus().ordinal());
        writeText(output, result.getOutput());
        writeText(output, result.getSource());
        output.flush();
        return bytes.toByteArray();
    }

    public static ConsoleResult decodeResult(byte[] data) throws IOException {
        DataInputStream input = open(data, TYPE_RESPONSE);
        String requestId = readText(input);
        ConsoleResult.Status status = enumValue(
                ConsoleResult.Status.values(), input.readUnsignedByte(), "status");
        return new ConsoleResult(requestId, status, readText(input), readText(input));
    }

    private static DataInputStream open(byte[] data, int expectedType) throws IOException {
        if (data == null || data.length < 7 || data.length > 65535) {
            throw new IOException("Invalid plugin message length");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION
                || input.readUnsignedByte() != expectedType) {
            throw new IOException("Unsupported plugin message");
        }
        return input;
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        String text = value == null ? "" : value;
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        output.writeUTF(text);
    }

    private static String readText(DataInputStream input) throws IOException {
        String value = input.readUTF();
        if (value.length() > MAX_TEXT) {
            throw new IOException("Plugin message text is too long");
        }
        return value;
    }

    private static <T> T enumValue(T[] values, int ordinal, String label) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("Invalid " + label);
        }
        return values[ordinal];
    }
}
