package haaa.shitbot.core.inventory;

import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.Translations;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Compact inventory PNG renderer with bounded render caching and concurrency. */
public final class InventoryImageRenderer {
    private static final int[] DISPLAY_SLOTS = new int[]{
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            0, 1, 2, 3, 4, 5, 6, 7, 8
    };
    private static final int[] EQUIPMENT_SLOTS = new int[]{
            InventorySnapshot.HELMET_SLOT,
            InventorySnapshot.CHESTPLATE_SLOT,
            InventorySnapshot.LEGGINGS_SLOT,
            InventorySnapshot.BOOTS_SLOT,
            InventorySnapshot.OFFHAND_SLOT
    };
    private final Settings.Inventory settings;
    private final Translations translations;
    private final InventoryStyle style;
    private final java.util.List<String> equipmentLabels;
    private final ItemIconResolver iconResolver;
    private final Semaphore renderPermits;
    private final Map<String, CachedRender> renderCache = new LinkedHashMap<String, CachedRender>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedRender> eldest) {
            return size() > 128;
        }
    };

    public InventoryImageRenderer(Settings.Inventory settings,
                                  Translations translations,
                                  ItemIconResolver iconResolver) {
        this.settings = settings;
        this.translations = translations;
        this.style = new InventoryStyle(settings.getTemplate());
        this.equipmentLabels = translations.getList("inventory.equipment-labels",
                java.util.Arrays.asList("H", "C", "L", "F", "O"));
        this.iconResolver = iconResolver;
        this.renderPermits = new Semaphore(settings.getMaximumConcurrentRenders(), true);
    }

    public byte[] render(InventorySnapshot snapshot, boolean live) throws Exception {
        String key = snapshot.getPlayerName() + '|' + snapshot.getCapturedAt() + '|' + live
                + '|' + iconResolver.getCacheGeneration();
        long now = System.currentTimeMillis();
        synchronized (renderCache) {
            CachedRender cached = renderCache.get(key);
            if (cached != null && now - cached.createdAt <= settings.getRenderCacheSeconds() * 1000L) {
                return cached.bytes.clone();
            }
        }

        renderPermits.acquire();
        try {
            byte[] bytes = renderUncached(snapshot, live);
            synchronized (renderCache) {
                renderCache.put(key, new CachedRender(now, bytes));
            }
            return bytes.clone();
        } finally {
            renderPermits.release();
        }
    }

    private byte[] renderUncached(InventorySnapshot snapshot, boolean live) throws Exception {
        int slot = settings.getSlotSize();
        int gap = style.slotGap;
        int padding = style.padding;
        int headerHeight = style.headerHeight;
        int footerHeight = style.footerHeight;
        int gridWidth = slot * 9 + gap * 8;
        int gridHeight = slot * 4 + gap * 3;
        int equipmentHeight = slot * 5 + gap * 4;
        int contentHeight = Math.max(gridHeight, equipmentHeight);
        int equipmentWidth = Math.max(
                slot + style.equipmentExtraWidth,
                style.equipmentIconOffset + slot);
        int requiredWidth = padding * 2 + gridWidth + style.gridEquipmentGap + equipmentWidth;
        int width = Math.max(settings.getWidth(), requiredWidth);
        int height = headerHeight + contentHeight + footerHeight + padding;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configureGraphics(graphics);
            paintBackground(graphics, width, height);

            Font titleFont = new Font(settings.getFontName(), Font.BOLD, style.titleFontSize);
            Font playerFont = new Font(settings.getFontName(), Font.BOLD, style.playerFontSize);
            Font smallFont = new Font(settings.getFontName(), Font.PLAIN, style.smallFontSize);
            Font amountFont = new Font(settings.getFontName(), Font.BOLD,
                    Math.max(style.minimumAmountFontSize, slot / 4));

            int left = (width - requiredWidth) / 2 + padding;
            int top = headerHeight;
            String title = settings.getTitle().replace("%player%", snapshot.getPlayerName());
            graphics.setFont(titleFont);
            graphics.setColor(style.titleColor);
            graphics.drawString(ellipsize(title, graphics.getFontMetrics(), width - padding * 2),
                    padding, style.titleY);

            graphics.setFont(playerFont);
            graphics.setColor(style.playerColor);
            graphics.drawString(snapshot.getPlayerName(), padding, style.playerY);

            String badge = translations.get(live ? "inventory.live" : "inventory.snapshot");
            graphics.setFont(smallFont);
            int badgeWidth = graphics.getFontMetrics().stringWidth(badge)
                    + style.badgeHorizontalPadding;
            int badgeX = width - padding - badgeWidth;
            graphics.setColor(live ? style.liveBadgeColor : style.snapshotBadgeColor);
            graphics.fillRoundRect(badgeX, style.badgeY, badgeWidth, style.badgeHeight,
                    style.statusBadgeRadius, style.statusBadgeRadius);
            graphics.setColor(style.badgeTextColor);
            int badgeTextY = style.badgeY
                    + (style.badgeHeight - graphics.getFontMetrics().getHeight()) / 2
                    + graphics.getFontMetrics().getAscent();
            graphics.drawString(badge, badgeX + style.badgeHorizontalPadding / 2, badgeTextY);

            graphics.setFont(amountFont);
            for (int index = 0; index < DISPLAY_SLOTS.length; index++) {
                int row = index / 9;
                int column = index % 9;
                int x = left + column * (slot + gap);
                int y = top + row * (slot + gap);
                paintSlot(graphics, x, y, slot, snapshot.getItem(DISPLAY_SLOTS[index]), amountFont);
            }

            int equipmentX = left + gridWidth + style.gridEquipmentGap;
            graphics.setFont(smallFont);
            graphics.setColor(style.equipmentTitleColor);
            graphics.drawString(translations.get("inventory.equipment-title"),
                    equipmentX, top - style.equipmentTitleOffsetY);
            for (int index = 0; index < EQUIPMENT_SLOTS.length; index++) {
                int y = top + index * (slot + gap);
                graphics.setFont(smallFont);
                graphics.setColor(style.secondaryTextColor);
                String equipmentLabel = index < equipmentLabels.size()
                        ? equipmentLabels.get(index)
                        : String.valueOf(index + 1);
                graphics.drawString(equipmentLabel, equipmentX, y + slot / 2 + 5);
                paintSlot(graphics, equipmentX + style.equipmentIconOffset, y, slot,
                        snapshot.getItem(EQUIPMENT_SLOTS[index]), amountFont);
            }

            int footerY = top + contentHeight + style.footerOffsetY;
            graphics.setFont(smallFont);
            graphics.setColor(style.secondaryTextColor);
            String timestamp = formatTimestamp(snapshot.getCapturedAt());
            String timeText = translations.format("inventory.data-time", "%time%", timestamp);
            int timeWidth = graphics.getFontMetrics().stringWidth(timeText);
            String summary = translations.format("inventory.summary",
                    "%occupied%", String.valueOf(snapshot.getOccupiedSlots()),
                    "%slots%", String.valueOf(InventorySnapshot.TOTAL_SLOTS),
                    "%items%", String.valueOf(snapshot.getTotalItemCount()),
                    "%server%", snapshot.getServerName());
            int summaryWidth = Math.max(80,
                    width - padding * 2 - timeWidth - style.gridEquipmentGap);
            graphics.drawString(ellipsize(summary, graphics.getFontMetrics(), summaryWidth), padding, footerY);
            graphics.drawString(timeText, width - padding - timeWidth, footerY);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("No PNG writer is available");
        }
        return output.toByteArray();
    }

    private void paintSlot(Graphics2D graphics,
                           int x,
                           int y,
                           int size,
                           InventorySnapshot.Item item,
                           Font amountFont) {
        graphics.setColor(style.slotBackgroundColor);
        graphics.fillRoundRect(x, y, size, size, style.slotRadius, style.slotRadius);
        graphics.setStroke(new BasicStroke(item != null && item.isEnchanted()
                ? style.enchantedBorderWidth
                : style.normalBorderWidth));
        graphics.setColor(item != null && item.isEnchanted()
                ? style.enchantedBorderColor
                : style.slotBorderColor);
        graphics.drawRoundRect(x, y, size, size, style.slotRadius, style.slotRadius);

        if (item == null) {
            return;
        }
        BufferedImage icon = iconResolver.resolve(item);
        int inset = Math.max(4, size / 10);
        int iconSize = size - inset * 2;
        if (icon != null) {
            Object previous = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(icon, x + inset, y + inset, iconSize, iconSize, null);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    previous == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : previous);
        } else {
            paintMissingTexture(graphics, x + inset, y + inset, iconSize);
        }

        if (item.getAmount() > 1) {
            String amount = String.valueOf(item.getAmount());
            graphics.setFont(amountFont);
            FontMetrics metrics = graphics.getFontMetrics();
            int textX = x + size - metrics.stringWidth(amount) - 4;
            int textY = y + size - 5;
            graphics.setColor(style.amountShadowColor);
            graphics.drawString(amount, textX + 1, textY + 1);
            graphics.setColor(style.amountTextColor);
            graphics.drawString(amount, textX, textY);
        }

        if (item.hasDurability()) {
            double remaining = 1.0D - (double) item.getDamage() / (double) item.getMaximumDurability();
            remaining = Math.max(0.0D, Math.min(1.0D, remaining));
            int barX = x + 4;
            int barY = y + size - 4;
            int barWidth = size - 8;
            graphics.setColor(style.durabilityBackgroundColor);
            graphics.fillRect(barX, barY, barWidth, 2);
            graphics.setColor(durabilityColor(remaining));
            graphics.fillRect(barX, barY, (int) Math.round(barWidth * remaining), 2);
        }
    }

    private void paintMissingTexture(Graphics2D graphics, int x, int y, int size) {
        int cell = Math.max(4, size / 4);
        for (int row = 0; row * cell < size; row++) {
            for (int column = 0; column * cell < size; column++) {
                graphics.setColor(((row + column) & 1) == 0
                        ? style.missingLightColor
                        : style.missingDarkColor);
                int width = Math.min(cell, size - column * cell);
                int height = Math.min(cell, size - row * cell);
                graphics.fillRect(x + column * cell, y + row * cell, width, height);
            }
        }
    }

    private Color durabilityColor(double remaining) {
        float hue = (float) (remaining / 3.0D);
        return Color.getHSBColor(hue, style.durabilitySaturation, style.durabilityBrightness);
    }

    private void paintBackground(Graphics2D graphics, int width, int height) {
        graphics.setColor(style.backgroundColor);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(style.cardColor);
        graphics.fillRoundRect(style.cardInset, style.cardInset,
                width - style.cardInset * 2, height - style.cardInset * 2,
                style.cardRadius, style.cardRadius);
    }

    private void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private String formatTimestamp(long capturedAt) {
        String pattern = translations.get("inventory.time-format");
        try {
            return new SimpleDateFormat(pattern, Locale.ROOT).format(new Date(capturedAt));
        } catch (IllegalArgumentException ignored) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new Date(capturedAt));
        }
    }

    private String ellipsize(String text, FontMetrics metrics, int maximumWidth) {
        if (metrics.stringWidth(text) <= maximumWidth) {
            return text;
        }
        String suffix = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > maximumWidth) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    private static final class CachedRender {
        private final long createdAt;
        private final byte[] bytes;

        private CachedRender(long createdAt, byte[] bytes) {
            this.createdAt = createdAt;
            this.bytes = bytes.clone();
        }
    }

    private static final class InventoryStyle {
        private final int padding;
        private final int slotGap;
        private final int headerHeight;
        private final int footerHeight;
        private final int gridEquipmentGap;
        private final int equipmentExtraWidth;
        private final int equipmentIconOffset;
        private final int cardInset;
        private final int titleY;
        private final int playerY;
        private final int badgeY;
        private final int badgeHeight;
        private final int badgeHorizontalPadding;
        private final int equipmentTitleOffsetY;
        private final int footerOffsetY;
        private final int titleFontSize;
        private final int playerFontSize;
        private final int smallFontSize;
        private final int minimumAmountFontSize;
        private final int cardRadius;
        private final int statusBadgeRadius;
        private final int slotRadius;
        private final float normalBorderWidth;
        private final float enchantedBorderWidth;
        private final Color backgroundColor;
        private final Color cardColor;
        private final Color titleColor;
        private final Color playerColor;
        private final Color liveBadgeColor;
        private final Color snapshotBadgeColor;
        private final Color badgeTextColor;
        private final Color equipmentTitleColor;
        private final Color secondaryTextColor;
        private final Color slotBackgroundColor;
        private final Color slotBorderColor;
        private final Color enchantedBorderColor;
        private final Color amountShadowColor;
        private final Color amountTextColor;
        private final Color durabilityBackgroundColor;
        private final Color missingLightColor;
        private final Color missingDarkColor;
        private final float durabilitySaturation;
        private final float durabilityBrightness;

        private InventoryStyle(ImageTemplate template) {
            padding = template.getInt("inventory.layout.padding", 8, 160, 24);
            slotGap = template.getInt("inventory.layout.slot-gap", 0, 48, 5);
            headerHeight = template.getInt("inventory.layout.header-height", 48, 240, 92);
            footerHeight = template.getInt("inventory.layout.footer-height", 24, 160, 54);
            gridEquipmentGap = template.getInt("inventory.layout.grid-equipment-gap", 0, 120, 24);
            equipmentExtraWidth = template.getInt("inventory.layout.equipment-extra-width", 32, 200, 72);
            equipmentIconOffset = template.getInt("inventory.layout.equipment-icon-offset", 0, 120, 28);
            cardInset = template.getInt("inventory.layout.card-inset", 0, 80, 12);
            titleY = template.getInt("inventory.layout.title-y", 16, 160, 38);
            playerY = template.getInt("inventory.layout.player-y", 24, 220, 66);
            badgeY = template.getInt("inventory.layout.badge-y", 0, 160, 24);
            badgeHeight = template.getInt("inventory.layout.badge-height", 16, 96, 28);
            badgeHorizontalPadding = template.getInt(
                    "inventory.layout.badge-horizontal-padding", 4, 100, 20);
            equipmentTitleOffsetY = template.getInt(
                    "inventory.layout.equipment-title-offset-y", 0, 80, 10);
            footerOffsetY = template.getInt("inventory.layout.footer-offset-y", 8, 120, 30);
            titleFontSize = fontSize(template, "title", 28);
            playerFontSize = fontSize(template, "player", 18);
            smallFontSize = fontSize(template, "small", 13);
            minimumAmountFontSize = fontSize(template, "minimum-amount", 12);
            cardRadius = radius(template, "card", 24);
            statusBadgeRadius = radius(template, "status-badge", 14);
            slotRadius = radius(template, "slot", 10);
            normalBorderWidth = template.getInt(
                    "inventory.strokes.normal-border-width-tenths", 1, 50, 10) / 10.0F;
            enchantedBorderWidth = template.getInt(
                    "inventory.strokes.enchanted-border-width-tenths", 1, 80, 20) / 10.0F;
            backgroundColor = color(template, "background", "#0A0D13");
            cardColor = color(template, "card", "#A61E283A");
            titleColor = color(template, "title", "#F6F8FC");
            playerColor = color(template, "player", "#AED6FF");
            liveBadgeColor = color(template, "live-badge", "#2B8954");
            snapshotBadgeColor = color(template, "snapshot-badge", "#80652E");
            badgeTextColor = color(template, "badge-text", "#FFFFFFFF");
            equipmentTitleColor = color(template, "equipment-title", "#A8B1C2");
            secondaryTextColor = color(template, "secondary-text", "#97A0B2");
            slotBackgroundColor = color(template, "slot-background", "#EB141923");
            slotBorderColor = color(template, "slot-border", "#DC495265");
            enchantedBorderColor = color(template, "enchanted-border", "#DC9767FF");
            amountShadowColor = color(template, "amount-shadow", "#BE000000");
            amountTextColor = color(template, "amount-text", "#FFFFFFFF");
            durabilityBackgroundColor = color(template, "durability-background", "#BE000000");
            missingLightColor = color(template, "missing-light", "#F100F1");
            missingDarkColor = color(template, "missing-dark", "#180018");
            durabilitySaturation = template.getInt(
                    "inventory.durability.saturation-percent", 0, 100, 90) / 100.0F;
            durabilityBrightness = template.getInt(
                    "inventory.durability.brightness-percent", 0, 100, 95) / 100.0F;
        }

        private static int fontSize(ImageTemplate template, String name, int fallback) {
            return template.getInt("inventory.fonts." + name, 8, 96, fallback);
        }

        private static int radius(ImageTemplate template, String name, int fallback) {
            return template.getInt("inventory.radii." + name, 0, 96, fallback);
        }

        private static Color color(ImageTemplate template, String name, String fallback) {
            return template.getColor("inventory.colors." + name, fallback);
        }
    }
}
