package haaa.shitbot.core.util;

import java.util.regex.Pattern;

public final class TextUtil {
    // Bedrock gamertags may contain spaces and non-ASCII letters; Java names remain a subset.
    private static final Pattern PLAYER_NAME = Pattern.compile("^[\\p{L}\\p{N}_ ]{1,16}$");
    private static final Pattern QQ_ID = Pattern.compile("^[1-9][0-9]{4,19}$");

    private TextUtil() {
    }

    public static boolean isValidPlayerName(String playerName) {
        return playerName != null && PLAYER_NAME.matcher(playerName.trim()).matches();
    }

    public static boolean isValidQqId(String qqId) {
        return qqId != null && QQ_ID.matcher(qqId.trim()).matches();
    }

    public static String color(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('&', '\u00A7');
    }

    public static String replace(String template, String key, Object value) {
        return template == null ? "" : template.replace(key, value == null ? "" : String.valueOf(value));
    }

    public static String singleLine(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.length() <= maximumLength) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, maximumLength - 3)) + "...";
    }
}
