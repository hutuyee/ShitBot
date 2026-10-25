package haaa.shitbot.core.database;

/** Persisted online-time state for one exact Minecraft player name. */
public final class PlayerStats {
    private final String playerName;
    private final String playerUuid;
    private final long totalOnlineSeconds;
    private final Long sessionStartedAt;

    public PlayerStats(String playerName,
                       String playerUuid,
                       long totalOnlineSeconds,
                       Long sessionStartedAt) {
        this.playerName = playerName == null ? "" : playerName;
        this.playerUuid = playerUuid == null ? "" : playerUuid;
        this.totalOnlineSeconds = Math.max(0L, totalOnlineSeconds);
        this.sessionStartedAt = sessionStartedAt;
    }

    public String getPlayerName() { return playerName; }
    public String getPlayerUuid() { return playerUuid; }
    public long getTotalOnlineSeconds() { return totalOnlineSeconds; }
    public Long getSessionStartedAt() { return sessionStartedAt; }
}
