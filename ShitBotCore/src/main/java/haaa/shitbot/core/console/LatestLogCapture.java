package haaa.shitbot.core.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

/** Captures newly appended text from the server's logs/latest.log file. */
public final class LatestLogCapture {
    private static final int MAX_LOG_LINES = 100;
    private static final int MAX_LOG_CHARACTERS = 4000;
    private static final int MAX_SOURCE_LINE_CHARACTERS = 65536;
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(\\\"?(?:authorization|access[-_ ]?token|token|password|passwd|pwd|secret)"
                    + "\\\"?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern DATABASE_URL = Pattern.compile(
            "(?i)\\b(?:jdbc:(?:mysql|mariadb):|mysql:)//[^\\s]+");
    private static final Pattern ANSI_OSC = Pattern.compile(
            "(?:\u001B\\]|\u009D)[^\u0007]*?(?:\u0007|\u001B\\\\|\u009C|$)");
    private static final Pattern ANSI_CSI = Pattern.compile(
            "(?:\u001B\\[|\u009B)[0-?]*[ -/]*[@-~]");
    private static final Pattern ANSI_TWO_CHARACTER = Pattern.compile("\u001B[@-_]");
    private static final Pattern MINECRAFT_FORMATTING = Pattern.compile(
            "(?i)\u00A7[0-9A-FK-ORX]");
    private static final Pattern OTHER_CONTROL_CHARACTERS = Pattern.compile(
            "[\\p{Cc}&&[^\\r\\n\\t]]");

    private final Path logFile;
    private final boolean existedAtStart;
    private final long startPosition;
    private final Object startFileKey;

    private LatestLogCapture(Path logFile,
                             boolean existedAtStart,
                             long startPosition,
                             Object startFileKey) {
        this.logFile = logFile;
        this.existedAtStart = existedAtStart;
        this.startPosition = startPosition;
        this.startFileKey = startFileKey;
    }

    public static LatestLogCapture begin() {
        return begin(defaultLogFile());
    }

    public static LatestLogCapture begin(Path logFile) {
        Path normalized = logFile == null
                ? defaultLogFile()
                : logFile.toAbsolutePath().normalize();
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized, BasicFileAttributes.class);
            if (attributes.isRegularFile()) {
                return new LatestLogCapture(
                        normalized, true, attributes.size(), attributes.fileKey());
            }
        } catch (IOException ignored) {
        }
        return new LatestLogCapture(normalized, false, 0L, null);
    }

    public Path getLogFile() {
        return logFile;
    }

    public String readNewContent() throws IOException {
        BasicFileAttributes current = Files.readAttributes(logFile, BasicFileAttributes.class);
        if (!current.isRegularFile()) {
            return "";
        }

        long position = startPosition(current);
        if (current.size() <= position) {
            return "";
        }

        SeekableByteChannel channel = Files.newByteChannel(logFile, StandardOpenOption.READ);
        channel.position(position);
        Reader reader = new BufferedReader(Channels.newReader(
                channel,
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE),
                8192));
        try {
            return readLimited(reader);
        } finally {
            reader.close();
        }
    }

    private long startPosition(BasicFileAttributes current) {
        if (!existedAtStart || current.size() < startPosition) {
            return 0L;
        }
        Object currentFileKey = current.fileKey();
        if (startFileKey != null && currentFileKey != null
                && !startFileKey.equals(currentFileKey)) {
            return 0L;
        }
        return startPosition;
    }

    private String readLimited(Reader reader) throws IOException {
        LimitedOutput captured = new LimitedOutput();
        StringBuilder line = new StringBuilder();
        boolean previousCarriageReturn = false;
        boolean pendingLine = false;
        int character;
        while ((character = reader.read()) != -1 && !captured.isFull()) {
            if (character == '\r') {
                captured.appendLine(line.toString());
                line.setLength(0);
                previousCarriageReturn = true;
                pendingLine = false;
                continue;
            }
            if (character == '\n') {
                if (!previousCarriageReturn) {
                    captured.appendLine(line.toString());
                    line.setLength(0);
                }
                previousCarriageReturn = false;
                pendingLine = false;
                continue;
            }
            previousCarriageReturn = false;
            pendingLine = true;
            if (line.length() < MAX_SOURCE_LINE_CHARACTERS) {
                line.append((char) character);
            }
        }
        if (pendingLine && !captured.isFull()) {
            captured.appendLine(line.toString());
        }
        return captured.output();
    }

    private static Path defaultLogFile() {
        return Paths.get("logs", "latest.log").toAbsolutePath().normalize();
    }

    private static String redactSensitive(String message) {
        String redacted = stripFormatting(message);
        redacted = BEARER_SECRET.matcher(redacted).replaceAll("Bearer <redacted>");
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1<redacted>");
        return DATABASE_URL.matcher(redacted).replaceAll("<redacted database URL>");
    }

    private static String stripFormatting(String message) {
        String clean = message == null ? "" : message;
        clean = ANSI_OSC.matcher(clean).replaceAll("");
        clean = ANSI_CSI.matcher(clean).replaceAll("");
        clean = ANSI_TWO_CHARACTER.matcher(clean).replaceAll("");
        clean = MINECRAFT_FORMATTING.matcher(clean).replaceAll("");
        return OTHER_CONTROL_CHARACTERS.matcher(clean).replaceAll("");
    }

    private static final class LimitedOutput {
        private final StringBuilder output = new StringBuilder();
        private int lines;

        private void appendLine(String line) {
            if (isFull()) {
                return;
            }
            String redacted = redactSensitive(line == null ? "" : line);
            if (output.length() > 0) {
                output.append('\n');
            }
            int remaining = MAX_LOG_CHARACTERS - output.length();
            output.append(redacted, 0, Math.min(redacted.length(), remaining));
            lines++;
        }

        private boolean isFull() {
            return lines >= MAX_LOG_LINES || output.length() >= MAX_LOG_CHARACTERS;
        }

        private String output() {
            return output.toString().trim();
        }
    }
}
