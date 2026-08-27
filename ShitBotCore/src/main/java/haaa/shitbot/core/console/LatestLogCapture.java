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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final Pattern PLAYER_CHAT_LINE = Pattern.compile(
            "(?i)(?:\\[[^\\]]*(?:async chat|chat thread)[^\\]]*\\]"
                    + "|\\]:\\s*(?:\\[not secure\\]\\s*)?<[^>\\r\\n]{1,64}>\\s+)");
    private static final Pattern PLAYER_LIFECYCLE_LINE = Pattern.compile(
            "(?i)\\b(?:joined the game|left the game|lost connection|logged in with entity id"
                    + "|issued server command|uuid of player|was kicked)\\b");
    private static final Pattern PROXY_PLAYER_CONNECTION_LINE = Pattern.compile(
            "(?i)(?:\\[connected player\\]|initialhandler|upstreambridge|serverconnector)"
                    + ".*\\b(?:connected|disconnected)\\b");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "(?i)(?<![0-9a-f])(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}|[0-9a-f]{32})(?![0-9a-f])");
    private static final Pattern IPV4_ADDRESS = Pattern.compile(
            "(?<![0-9.])(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})\\.){3}"
                    + "(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})(?::[0-9]{1,5})?(?![0-9.])");
    private static final Pattern IPV6_ADDRESS = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?:"
                    + "(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}"
                    + "|(?:[0-9a-f]{1,4}:){1,7}:"
                    + "|(?:[0-9a-f]{1,4}:){1,6}:[0-9a-f]{1,4}"
                    + "|(?:[0-9a-f]{1,4}:){1,5}(?::[0-9a-f]{1,4}){1,2}"
                    + "|(?:[0-9a-f]{1,4}:){1,4}(?::[0-9a-f]{1,4}){1,3}"
                    + "|(?:[0-9a-f]{1,4}:){1,3}(?::[0-9a-f]{1,4}){1,4}"
                    + "|(?:[0-9a-f]{1,4}:){1,2}(?::[0-9a-f]{1,4}){1,5}"
                    + "|[0-9a-f]{1,4}:(?:(?::[0-9a-f]{1,4}){1,6})"
                    + "|:(?:(?::[0-9a-f]{1,4}){1,7}|:)"
                    + ")(?![0-9a-f:])");

    private final Path logFile;
    private final boolean existedAtStart;
    private final long startPosition;
    private final Object startFileKey;
    private final List<Pattern> playerNamePatterns;

    private LatestLogCapture(Path logFile,
                             boolean existedAtStart,
                             long startPosition,
                             Object startFileKey,
                             List<Pattern> playerNamePatterns) {
        this.logFile = logFile;
        this.existedAtStart = existedAtStart;
        this.startPosition = startPosition;
        this.startFileKey = startFileKey;
        this.playerNamePatterns = playerNamePatterns;
    }

    public static LatestLogCapture begin() {
        return begin(defaultLogFile(), Collections.<String>emptyList());
    }

    public static LatestLogCapture begin(Iterable<String> playerNames) {
        return begin(defaultLogFile(), playerNames);
    }

    public static LatestLogCapture begin(Path logFile) {
        return begin(logFile, Collections.<String>emptyList());
    }

    public static LatestLogCapture begin(Path logFile, Iterable<String> playerNames) {
        Path normalized = logFile == null
                ? defaultLogFile()
                : logFile.toAbsolutePath().normalize();
        List<Pattern> privacyPatterns = playerNamePatterns(playerNames);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized, BasicFileAttributes.class);
            if (attributes.isRegularFile()) {
                return new LatestLogCapture(
                        normalized, true, attributes.size(), attributes.fileKey(), privacyPatterns);
            }
        } catch (IOException ignored) {
        }
        return new LatestLogCapture(normalized, false, 0L, null, privacyPatterns);
    }

    public Path getLogFile() {
        return logFile;
    }

    public String readNewContent() throws IOException {
        return readNewContent(Collections.<String>emptyList());
    }

    public String readNewContent(Iterable<String> additionalPlayerNames) throws IOException {
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
            return readLimited(reader, mergedPlayerNamePatterns(additionalPlayerNames));
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

    private String readLimited(Reader reader, List<Pattern> privacyPatterns) throws IOException {
        LimitedOutput captured = new LimitedOutput(privacyPatterns);
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

    private List<Pattern> mergedPlayerNamePatterns(Iterable<String> additionalPlayerNames) {
        List<Pattern> additional = playerNamePatterns(additionalPlayerNames);
        if (additional.isEmpty()) {
            return playerNamePatterns;
        }
        List<Pattern> merged = new ArrayList<Pattern>(playerNamePatterns.size() + additional.size());
        merged.addAll(playerNamePatterns);
        merged.addAll(additional);
        return merged;
    }

    private static List<Pattern> playerNamePatterns(Iterable<String> playerNames) {
        if (playerNames == null) {
            return Collections.emptyList();
        }
        Set<String> claimed = new LinkedHashSet<String>();
        List<Pattern> patterns = new ArrayList<Pattern>();
        for (String playerName : playerNames) {
            if (playerName == null) {
                continue;
            }
            String clean = playerName.trim();
            if (clean.isEmpty() || clean.length() > 64
                    || !claimed.add(clean.toLowerCase(Locale.ROOT))) {
                continue;
            }
            patterns.add(Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(clean) + "(?![A-Za-z0-9_])"));
        }
        return patterns.isEmpty()
                ? Collections.<Pattern>emptyList()
                : Collections.unmodifiableList(patterns);
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

    private static String filterPlayerPrivacy(String message, List<Pattern> playerPatterns) {
        String clean = stripFormatting(message);
        if (PLAYER_CHAT_LINE.matcher(clean).find()
                || PLAYER_LIFECYCLE_LINE.matcher(clean).find()
                || PROXY_PLAYER_CONNECTION_LINE.matcher(clean).find()) {
            return null;
        }
        for (Pattern playerPattern : playerPatterns) {
            clean = playerPattern.matcher(clean).replaceAll("<player>");
        }
        clean = UUID_VALUE.matcher(clean).replaceAll("<uuid>");
        clean = IPV4_ADDRESS.matcher(clean).replaceAll("<ip>");
        clean = IPV6_ADDRESS.matcher(clean).replaceAll("<ip>");
        return redactSensitive(clean);
    }

    private static final class LimitedOutput {
        private final StringBuilder output = new StringBuilder();
        private final List<Pattern> playerPatterns;
        private int lines;

        private LimitedOutput(List<Pattern> playerPatterns) {
            this.playerPatterns = playerPatterns;
        }

        private void appendLine(String line) {
            if (isFull()) {
                return;
            }
            String redacted = filterPlayerPrivacy(line == null ? "" : line, playerPatterns);
            if (redacted == null) {
                return;
            }
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
