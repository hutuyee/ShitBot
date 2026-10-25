package haaa.shitbot.core.database;

public final class BindingRecord {
    private final String playerName;
    private final String playerUuid;
    private final String qqId;
    private final long createdAt;
    private final long updatedAt;

    public BindingRecord(String playerName, String playerUuid, String qqId, long createdAt, long updatedAt) {
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.qqId = qqId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getPlayerName() { return playerName; }
    public String getPlayerUuid() { return playerUuid; }
    public String getQqId() { return qqId; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
}
