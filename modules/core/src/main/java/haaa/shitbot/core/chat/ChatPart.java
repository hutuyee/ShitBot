package haaa.shitbot.core.chat;

/** A piece of a rich chat message, optionally opening an URL when clicked. */
public final class ChatPart {
    private final String text;
    private final String clickUrl;
    private final String hoverText;

    public ChatPart(String text, String clickUrl, String hoverText) {
        this.text = text == null ? "" : text;
        this.clickUrl = normalizeUrl(clickUrl);
        this.hoverText = hoverText == null ? "" : hoverText;
    }

    public static ChatPart text(String text) {
        return new ChatPart(text, null, null);
    }

    public static ChatPart link(String text, String url, String hoverText) {
        return new ChatPart(text, url, hoverText);
    }

    public String getText() {
        return text;
    }

    public String getClickUrl() {
        return clickUrl;
    }

    public String getHoverText() {
        return hoverText;
    }

    public boolean hasClickUrl() {
        return clickUrl != null;
    }

    private static String normalizeUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.regionMatches(true, 0, "https://", 0, 8)
                || trimmed.regionMatches(true, 0, "http://", 0, 7)) {
            return trimmed;
        }
        return null;
    }
}
