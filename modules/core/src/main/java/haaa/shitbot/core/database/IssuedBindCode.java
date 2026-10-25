package haaa.shitbot.core.database;

public final class IssuedBindCode {
    private final String playerName;
    private final String code;
    private final long expiresAt;

    public IssuedBindCode(String playerName, String code, long expiresAt) {
        this.playerName = playerName;
        this.code = code;
        this.expiresAt = expiresAt;
    }

    public String getPlayerName() { return playerName; }
    public String getCode() { return code; }
    public long getExpiresAt() { return expiresAt; }
}
