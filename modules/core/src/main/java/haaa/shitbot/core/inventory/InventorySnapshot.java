package haaa.shitbot.core.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    public static final int FORMAT_VERSION = 3;
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

    public String getPlayerName() { return playerName; }
    public String getPlayerUuid() { return playerUuid; }
    public String getServerName() { return serverName; }
    public long getCapturedAt() { return capturedAt; }
    public List<Item> getItems() { return items; }

    public Item getItem(int slot) {
        return slot < 0 || slot >= slots.length ? null : slots[slot];
    }

    public int getOccupiedSlots() { return items.size(); }

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
        private static final int MAX_MODEL_COMPONENT_VALUES = 16;

        private final int slot;
        private final String registryId;
        private final String materialName;
        private final String displayName;
        private final int amount;
        private final int damage;
        private final int maximumDurability;
        private final Integer customModelData;
        private final String itemModel;
        private final List<Float> customModelFloats;
        private final List<Boolean> customModelFlags;
        private final List<String> customModelStrings;
        private final List<Integer> customModelColors;
        private final String profileTextureHash;
        private final boolean enchanted;
        private final boolean unbreakable;

        /** Backward-compatible constructor used by old snapshots and platform bridges. */
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
            this(slot, registryId, materialName, displayName, amount, damage, maximumDurability,
                    customModelData, "", null, null, null, null, "", enchanted, unbreakable);
        }

        /** Backward-compatible constructor for format-2 snapshots. */
        public Item(int slot,
                    String registryId,
                    String materialName,
                    String displayName,
                    int amount,
                    int damage,
                    int maximumDurability,
                    Integer customModelData,
                    String itemModel,
                    List<Float> customModelFloats,
                    List<Boolean> customModelFlags,
                    List<String> customModelStrings,
                    List<Integer> customModelColors,
                    boolean enchanted,
                    boolean unbreakable) {
            this(slot, registryId, materialName, displayName, amount, damage, maximumDurability,
                    customModelData, itemModel, customModelFloats, customModelFlags,
                    customModelStrings, customModelColors, "", enchanted, unbreakable);
        }

        public Item(int slot,
                    String registryId,
                    String materialName,
                    String displayName,
                    int amount,
                    int damage,
                    int maximumDurability,
                    Integer customModelData,
                    String itemModel,
                    List<Float> customModelFloats,
                    List<Boolean> customModelFlags,
                    List<String> customModelStrings,
                    List<Integer> customModelColors,
                    String profileTextureHash,
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
            this.itemModel = cleanOptionalIdentifier(itemModel);
            this.customModelFloats = immutableFloats(customModelFloats);
            this.customModelFlags = immutableFlags(customModelFlags);
            this.customModelStrings = immutableStrings(customModelStrings);
            this.customModelColors = immutableColors(customModelColors);
            this.profileTextureHash = cleanTextureHash(profileTextureHash);
            this.enchanted = enchanted;
            this.unbreakable = unbreakable;
        }

        public int getSlot() { return slot; }
        public String getRegistryId() { return registryId; }
        public String getMaterialName() { return materialName; }
        public String getDisplayName() { return displayName; }
        public int getAmount() { return amount; }
        public int getDamage() { return damage; }
        public int getMaximumDurability() { return maximumDurability; }
        public Integer getCustomModelData() { return customModelData; }
        public String getItemModel() { return itemModel; }
        public List<Float> getCustomModelFloats() { return customModelFloats; }
        public List<Boolean> getCustomModelFlags() { return customModelFlags; }
        public List<String> getCustomModelStrings() { return customModelStrings; }
        public List<Integer> getCustomModelColors() { return customModelColors; }
        public String getProfileTextureHash() { return profileTextureHash; }
        public boolean isEnchanted() { return enchanted; }
        public boolean isUnbreakable() { return unbreakable; }

        public boolean hasDurability() {
            return !unbreakable && maximumDurability > 0;
        }

        /** In pre-flattening versions ItemStack durability is also the item data value. */
        public int getLegacyData() {
            return maximumDurability <= 0 ? damage : 0;
        }

        public float getCustomModelFloat(int index, float fallback) {
            if (index >= 0 && index < customModelFloats.size()) {
                Float value = customModelFloats.get(index);
                return value == null || Float.isNaN(value.floatValue()) || Float.isInfinite(value.floatValue())
                        ? fallback : value.floatValue();
            }
            if (index == 0 && customModelData != null) {
                return customModelData.floatValue();
            }
            return fallback;
        }

        public boolean getCustomModelFlag(int index, boolean fallback) {
            return index >= 0 && index < customModelFlags.size()
                    ? customModelFlags.get(index).booleanValue() : fallback;
        }

        public String getCustomModelString(int index) {
            return index >= 0 && index < customModelStrings.size()
                    ? customModelStrings.get(index) : "";
        }

        public String iconCacheKey() {
            StringBuilder key = new StringBuilder(registryId)
                    .append('#').append(getLegacyData())
                    .append('#').append(customModelData == null ? "" : customModelData)
                    .append('#').append(itemModel)
                    .append('#').append(profileTextureHash);
            appendValues(key, customModelFloats);
            appendValues(key, customModelFlags);
            appendValues(key, customModelStrings);
            appendValues(key, customModelColors);
            return key.toString();
        }

        private static void appendValues(StringBuilder builder, List<?> values) {
            builder.append('#');
            for (Object value : values) {
                builder.append(value).append(',');
            }
        }

        private static String cleanIdentifier(String registryId, String materialName) {
            String candidate = clean(registryId, "", 256).toLowerCase(Locale.ROOT);
            if (!candidate.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || candidate.contains("..")) {
                String fallback = clean(materialName, "unknown", 128)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_./-]", "_");
                candidate = "minecraft:" + fallback;
            }
            return candidate;
        }

        private static String cleanOptionalIdentifier(String value) {
            String candidate = clean(value, "", 256).toLowerCase(Locale.ROOT);
            if (candidate.isEmpty()) {
                return "";
            }
            if (!candidate.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || candidate.contains("..")) {
                return "";
            }
            return candidate;
        }

        private static String cleanTextureHash(String value) {
            String candidate = clean(value, "", 128).toLowerCase(Locale.ROOT);
            return candidate.matches("[0-9a-f]{32,128}") ? candidate : "";
        }

        private static List<Float> immutableFloats(List<Float> input) {
            List<Float> output = new ArrayList<Float>();
            if (input != null) {
                for (Float value : input) {
                    if (output.size() >= MAX_MODEL_COMPONENT_VALUES) break;
                    if (value != null && !Float.isNaN(value.floatValue()) && !Float.isInfinite(value.floatValue())) {
                        output.add(value);
                    }
                }
            }
            return Collections.unmodifiableList(output);
        }

        private static List<Boolean> immutableFlags(List<Boolean> input) {
            List<Boolean> output = new ArrayList<Boolean>();
            if (input != null) {
                for (Boolean value : input) {
                    if (output.size() >= MAX_MODEL_COMPONENT_VALUES) break;
                    if (value != null) output.add(value);
                }
            }
            return Collections.unmodifiableList(output);
        }

        private static List<String> immutableStrings(List<String> input) {
            List<String> output = new ArrayList<String>();
            if (input != null) {
                for (String value : input) {
                    if (output.size() >= MAX_MODEL_COMPONENT_VALUES) break;
                    output.add(clean(value, "", 128));
                }
            }
            return Collections.unmodifiableList(output);
        }

        private static List<Integer> immutableColors(List<Integer> input) {
            List<Integer> output = new ArrayList<Integer>();
            if (input != null) {
                for (Integer value : input) {
                    if (output.size() >= MAX_MODEL_COMPONENT_VALUES) break;
                    if (value != null) output.add(value);
                }
            }
            return Collections.unmodifiableList(output);
        }

        private static String humanize(String materialName) {
            String[] words = clean(materialName, "Unknown").toLowerCase(Locale.ROOT).split("_");
            StringBuilder builder = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) continue;
                if (builder.length() > 0) builder.append(' ');
                builder.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) builder.append(word.substring(1));
            }
            return builder.length() == 0 ? "Unknown" : builder.toString();
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
