package haaa.shitbot.core.inventory;

import haaa.shitbot.core.config.Translations;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain template data using the same snapshots and item textures as the native inventory image. */
public final class InventoryTemplateData {
    private final Translations translations;
    private final ItemIconResolver icons;

    public InventoryTemplateData(Translations translations, ItemIconResolver icons) {
        this.translations = translations;
        this.icons = icons;
    }

    public Map<String, Object> create(String player, InventorySnapshot snapshot, boolean live) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("player", snapshot == null ? player : snapshot.getPlayerName());
        data.put("available", Boolean.valueOf(snapshot != null));
        data.put("live", Boolean.valueOf(live));
        data.put("title", translations.format("inventory.title", "%player%", String.valueOf(data.get("player"))));
        data.put("unavailable", translations.get("inventory.no-snapshot"));
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("equipment", translations.get("inventory.equipment-title"));
        labels.put("storage", translations.get("inventory.storage-title"));
        labels.put("hotbar", translations.get("inventory.hotbar-title"));
        data.put("labels", labels);
        data.put("equipment", Collections.emptyList());
        data.put("storage", Collections.emptyList());
        data.put("hotbar", Collections.emptyList());
        data.put("slots", Collections.emptyList());
        if (snapshot == null) {
            return data;
        }
        data.put("player-uuid", snapshot.getPlayerUuid());
        data.put("server", snapshot.getServerName());
        data.put("captured-at", Long.valueOf(snapshot.getCapturedAt()));
        data.put("status", translations.get(live ? "inventory.live" : "inventory.snapshot"));
        DateTimeFormatter timeFormat;
        try {
            timeFormat = DateTimeFormatter.ofPattern(translations.get("inventory.time-format"));
        } catch (IllegalArgumentException exception) {
            timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        }
        String time = timeFormat.format(Instant.ofEpochMilli(snapshot.getCapturedAt()).atZone(ZoneId.systemDefault()));
        data.put("captured-time", time);
        data.put("data-time", translations.format("inventory.data-time", "%time%", time));
        data.put("occupied", Integer.valueOf(snapshot.getOccupiedSlots()));
        data.put("total-items", Integer.valueOf(snapshot.getTotalItemCount()));
        data.put("summary", translations.format("inventory.summary",
                "%occupied%", String.valueOf(snapshot.getOccupiedSlots()),
                "%slots%", String.valueOf(InventorySnapshot.TOTAL_SLOTS),
                "%items%", String.valueOf(snapshot.getTotalItemCount()), "%server%", snapshot.getServerName()));

        List<Map<String, Object>> slots = new ArrayList<Map<String, Object>>(InventorySnapshot.TOTAL_SLOTS);
        Map<String, String> encodedIcons = new HashMap<String, String>();
        for (int slot = 0; slot < InventorySnapshot.TOTAL_SLOTS; slot++) {
            slots.add(slotData(slot, snapshot.getItem(slot), encodedIcons));
        }
        List<Map<String, Object>> equipment = new ArrayList<Map<String, Object>>();
        int[] equipmentSlots = {InventorySnapshot.HELMET_SLOT, InventorySnapshot.CHESTPLATE_SLOT,
                InventorySnapshot.LEGGINGS_SLOT, InventorySnapshot.BOOTS_SLOT, InventorySnapshot.OFFHAND_SLOT};
        for (int slot : equipmentSlots) {
            equipment.add(slots.get(slot));
        }
        data.put("slots", slots);
        data.put("equipment", equipment);
        data.put("storage", new ArrayList<Map<String, Object>>(slots.subList(9, 36)));
        data.put("hotbar", new ArrayList<Map<String, Object>>(slots.subList(0, 9)));
        return data;
    }

    private Map<String, Object> slotData(int slot, InventorySnapshot.Item item,
                                        Map<String, String> encodedIcons) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("slot", Integer.valueOf(slot));
        data.put("empty", Boolean.valueOf(item == null));
        data.put("icon", "");
        data.put("amount", Integer.valueOf(item == null ? 0 : item.getAmount()));
        data.put("amount-text", item == null || item.getAmount() <= 1 ? "" : String.valueOf(item.getAmount()));
        data.put("has-durability", Boolean.valueOf(item != null && item.hasDurability()));
        data.put("enchanted", Boolean.valueOf(item != null && item.isEnchanted()));
        data.put("border-color", item != null && item.isEnchanted() ? "#9170B8" : "#CCD5DF");
        if (item == null) {
            return data;
        }
        data.put("registry-id", item.getRegistryId());
        data.put("material-name", item.getMaterialName());
        data.put("name", item.getDisplayName());
        data.put("damage", Integer.valueOf(item.getDamage()));
        data.put("maximum-durability", Integer.valueOf(item.getMaximumDurability()));
        data.put("durability", Integer.valueOf(Math.max(0, item.getMaximumDurability() - item.getDamage())));
        String key = item.iconCacheKey();
        String icon = encodedIcons.get(key);
        if (icon == null) {
            icon = encodeIcon(icons.resolve(item));
            encodedIcons.put(key, icon);
        }
        data.put("icon", icon);
        return data;
    }

    private String encodeIcon(BufferedImage source) throws IOException {
        // Bound data size independently of resource-pack texture resolution.
        BufferedImage icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        try {
            if (source != null) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.drawImage(source, 0, 0, 64, 64, null);
            } else {
                graphics.setColor(new Color(225, 231, 237));
                graphics.fillRoundRect(4, 4, 56, 56, 12, 12);
                graphics.setColor(new Color(89, 99, 110));
                graphics.setFont(new Font("SansSerif", Font.BOLD, 36));
                graphics.drawString("?", (64 - graphics.getFontMetrics().stringWidth("?")) / 2, 45);
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(icon, "png", output)) throw new IOException("No PNG writer is available");
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
