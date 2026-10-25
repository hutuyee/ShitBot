package haaa.shitbot.api;

import java.util.Optional;

/** Immutable whitelist entry. An entry without a QQ owner grants login only. */
public final class PlayerBinding {
    private final String playerName;
    private final String playerUuid;
    private final String qqId;
    private final long createdAt;
    private final long updatedAt;

    public PlayerBinding(String playerName, String playerUuid, String qqId,
                         long createdAt, long updatedAt) {
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.qqId = qqId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Exact, case-sensitive Minecraft name. */
    public String getPlayerName() { return playerName; }
    public Optional<String> getPlayerUuid() { return optional(playerUuid); }
    public Optional<String> getQqId() { return optional(qqId); }
    public boolean hasQqOwner() { return getQqId().isPresent(); }
    /** Unix timestamp in milliseconds. */
    public long getCreatedAt() { return createdAt; }
    /** Unix timestamp in milliseconds. */
    public long getUpdatedAt() { return updatedAt; }

    private static Optional<String> optional(String value) {
        return value == null || value.isEmpty() ? Optional.<String>empty() : Optional.of(value);
    }
}
