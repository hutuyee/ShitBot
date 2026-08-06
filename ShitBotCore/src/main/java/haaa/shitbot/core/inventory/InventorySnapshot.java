package haaa.shitbot.core.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Platform-neutral, immutable player inventory snapshot.
 *
 * <p>Slots use a stable layout independent of Bukkit versions:</p>
 * <ul>
 *     <li>0-8: hotbar</li>
 *     <li>9-35: main storage</li>
 *     <li>36: boots, 37: leggings, 38: chestplate, 39: helmet</li>
 *     <li>40: offhand</li>
 * </ul>
 */
public final class InventorySnapshot {
    public static final int FORMAT_VERSION = 1;
    public static final int STORAGE_SIZE = 36;
    public static final int BOOTS_SLOT = 36;
    public static final int LEGGINGS_SLOT = 37;
    public static final int CHESTPLATE_SLOT = 38;
    public static final int HELMET_SLOT = 39;
    public static final int OFFHAND_SLOT = 40;
    public static final int TOTAL_SLOTS = 41;

    private final String playerName;
    private final String playerUuid;
    private final String serverName;
    private final long capturedAt;
    private final List<Item> items;
    private final Item[] slots;

    public InventorySnapshot(String playerName,
                             String playerUuid,
                             String serverName,
                             long capturedAt,
                             List<Item> items) {
        this.playerName = clean(playerName, "unknown", 16);
        this.playerUuid = clean(playerUuid, "", 36);
        this.serverName = clean(serverName, "unknown", 128);
        this.capturedAt = Math.max(0L, capturedAt);
        Item[] normalizedSlots = new Item[TOTAL_SLOTS];
        if (items != null) {
            for (Item item : items) {
                if (item != null && item.getSlot() >= 0 && item.getSlot() < TOTAL_SLOTS) {
                    normalizedSlots[item.getSlot()] = item;
                }
            }
        }
        List<Item> copy = new ArrayList<Item>();
        for (Item item : normalizedSlots) {
            if (item != null) {
                copy.add(item);
            }
        }
        this.slots = normalizedSlots;
        this.items = Collections.unmodifiableList(copy);
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public String getServerName() {
        return serverName;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public List<Item> getItems() {
        return items;
    }

    public Item getItem(int slot) {
        return slot < 0 || slot >= slots.length ? null : slots[slot];
    }

    public int getOccupiedSlots() {
        return items.size();
    }

    public int getTotalItemCount() {
        int result = 0;
        for (Item item : items) {
            result += item.getAmount();
        }
        return result;
    }

    private static String clean(String value, String fallback) {
        return clean(value, fallback, Integer.MAX_VALUE);
    }

    private static String clean(String value, String fallback, int maximumLength) {
        String selected = value == null ? fallback : value.trim();
        if (selected == null || selected.isEmpty()) {
            selected = fallback == null ? "" : fallback;
        }
        return selected.length() <= maximumLength
                ? selected : selected.substring(0, maximumLength);
    }

    public static final class Item {
        private final int slot;
        private final String registryId;
        private final String materialName;
        private final String displayName;
        private final int amount;
        private final int damage;
        private final int maximumDurability;
        private final Integer customModelData;
        private final boolean enchanted;
        private final boolean unbreakable;

        public Item(int slot,
                    String registryId,
                    String materialName,
                    String displayName,
                    int amount,
                    int damage,
                    int maximumDurability,
                    Integer customModelData,
                    boolean enchanted,
                    boolean unbreakable) {
            this.slot = slot;
            this.registryId = cleanIdentifier(registryId, materialName);
            this.materialName = clean(materialName, "UNKNOWN", 128);
            this.displayName = clean(displayName, humanize(this.materialName), 256);
            this.amount = clamp(amount, 1, 1_000_000);
            this.maximumDurability = Math.max(0, maximumDurability);
            this.damage = this.maximumDurability <= 0
                    ? Math.max(0, damage)
                    : clamp(damage, 0, this.maximumDurability);
            this.customModelData = customModelData;
            this.enchanted = enchanted;
            this.unbreakable = unbreakable;
        }

        public int getSlot() {
            return slot;
        }

        public String getRegistryId() {
            return registryId;
        }

        public String getMaterialName() {
            return materialName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getAmount() {
            return amount;
        }

        public int getDamage() {
            return damage;
        }

        public int getMaximumDurability() {
            return maximumDurability;
        }

        public Integer getCustomModelData() {
            return customModelData;
        }

        public boolean isEnchanted() {
            return enchanted;
        }

        public boolean isUnbreakable() {
            return unbreakable;
        }

        public boolean hasDurability() {
            return !unbreakable && maximumDurability > 0;
        }

        private static String cleanIdentifier(String registryId, String materialName) {
            String candidate = clean(registryId, "", 256).toLowerCase(java.util.Locale.ROOT);
            if (!candidate.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || candidate.contains("..")) {
                String fallback = clean(materialName, "unknown", 128)
                        .toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9_./-]", "_");
                candidate = "minecraft:" + fallback;
            }
            return candidate;
        }

        private static String humanize(String materialName) {
            String[] words = clean(materialName, "Unknown").toLowerCase(java.util.Locale.ROOT).split("_");
            StringBuilder builder = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    builder.append(word.substring(1));
                }
            }
            return builder.length() == 0 ? "Unknown" : builder.toString();
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
