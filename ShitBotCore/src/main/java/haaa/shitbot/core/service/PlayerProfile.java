package haaa.shitbot.core.service;

/** Immutable built-in profile data used by Java and external image renderers. */
public final class PlayerProfile {
    private final String playerName;
    private final String skinUrl;
    private final String permissionGroup;
    private final String points;
    private final long totalOnlineSeconds;
    private final boolean online;

    public PlayerProfile(String playerName,
                         String skinUrl,
                         String permissionGroup,
                         String points,
                         long totalOnlineSeconds,
                         boolean online) {
        this.playerName = playerName == null ? "" : playerName;
        this.skinUrl = skinUrl == null ? "" : skinUrl;
        this.permissionGroup = clean(permissionGroup);
        this.points = clean(points);
        this.totalOnlineSeconds = Math.max(0L, totalOnlineSeconds);
        this.online = online;
    }

    public String getPlayerName() { return playerName; }
    public String getSkinUrl() { return skinUrl; }
    public String getPermissionGroup() { return permissionGroup; }
    public String getPoints() { return points; }
    public long getTotalOnlineSeconds() { return totalOnlineSeconds; }
    public boolean isOnline() { return online; }

    private static String clean(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.startsWith("%") && trimmed.endsWith("%") ? "" : trimmed;
    }
}
