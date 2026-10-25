package haaa.shitbot.api;

import java.util.Objects;
import java.util.Optional;

/** Immutable inventory query outcome. Expected missing-data/access outcomes are not exceptions. */
public final class InventoryImageResult {
    public enum Status {
        SUCCESS,
        NOT_BOUND,
        PLAYER_NOT_BOUND,
        NO_SNAPSHOT,
        DISABLED
    }

    private final Status status;
    private final ImageRenderResult image;
    private final String playerName;
    private final String serverName;
    private final long capturedAt;
    private final boolean live;

    private InventoryImageResult(Status status, ImageRenderResult image, String playerName,
                                 String serverName, long capturedAt, boolean live) {
        this.status = status;
        this.image = image;
        this.playerName = playerName;
        this.serverName = serverName;
        this.capturedAt = capturedAt;
        this.live = live;
    }

    public static InventoryImageResult status(Status status) {
        Objects.requireNonNull(status, "status");
        if (status == Status.SUCCESS) throw new IllegalArgumentException("SUCCESS requires an image");
        return new InventoryImageResult(status, null, "", "", 0L, false);
    }

    public static InventoryImageResult success(ImageRenderResult image, String playerName,
                                                String serverName, long capturedAt, boolean live) {
        return new InventoryImageResult(Status.SUCCESS, Objects.requireNonNull(image, "image"),
                Objects.requireNonNull(playerName, "playerName"),
                Objects.requireNonNull(serverName, "serverName"), Math.max(0L, capturedAt), live);
    }

    public Status getStatus() { return status; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
    public Optional<ImageRenderResult> getImage() { return Optional.ofNullable(image); }
    /** Empty on unsuccessful queries. */
    public String getPlayerName() { return playerName; }
    /** Empty on unsuccessful queries. */
    public String getServerName() { return serverName; }
    /** Snapshot time in Unix milliseconds, or zero on unsuccessful queries. */
    public long getCapturedAt() { return capturedAt; }
    /** True only when this query captured an online player's inventory. */
    public boolean isLive() { return live; }
}
