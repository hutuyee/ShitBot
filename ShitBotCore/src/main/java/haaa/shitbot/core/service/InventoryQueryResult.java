package haaa.shitbot.core.service;

import haaa.shitbot.core.inventory.InventorySnapshot;

public final class InventoryQueryResult {
    public enum Status {
        SUCCESS,
        NOT_BOUND,
        PLAYER_NOT_BOUND,
        NO_SNAPSHOT,
        DISABLED
    }

    private final Status status;
    private final byte[] image;
    private final InventorySnapshot snapshot;
    private final boolean live;

    private InventoryQueryResult(Status status, byte[] image, InventorySnapshot snapshot, boolean live) {
        this.status = status;
        this.image = image;
        this.snapshot = snapshot;
        this.live = live;
    }

    public static InventoryQueryResult status(Status status) {
        return new InventoryQueryResult(status, null, null, false);
    }

    public static InventoryQueryResult success(byte[] image, InventorySnapshot snapshot, boolean live) {
        return new InventoryQueryResult(Status.SUCCESS, image, snapshot, live);
    }

    public Status getStatus() {
        return status;
    }

    public byte[] getImage() {
        return image;
    }

    public InventorySnapshot getSnapshot() {
        return snapshot;
    }

    public boolean isLive() {
        return live;
    }
}
