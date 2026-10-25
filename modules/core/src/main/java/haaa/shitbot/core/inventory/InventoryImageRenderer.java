package haaa.shitbot.core.inventory;

import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.image.NativeImageDrawing;

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
    private final haaa.shitbot.core.image.ImageBaseCache baseCache =
            new haaa.shitbot.core.image.ImageBaseCache();
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

    public void prepareBase() {
        Layout layout = new Layout();
        baseCache.prepare("inventory", layout.width, layout.height, graphics -> paintBase(graphics, layout));
    }

    public void clear() {
        baseCache.clear();
        synchronized (renderCache) { renderCache.clear(); }
    }

    private final class Layout {
        int slot = settings.getSlotSize();
        int gap = style.slotGap;
        int padding = style.padding;
        int titleY = Math.max(style.titleY, style.cardInset + style.titleFontSize + 12);
        int playerY = Math.max(style.playerY, titleY + style.playerFontSize + 8);
        int badgeY = Math.max(style.badgeY, titleY - style.titleFontSize + 2);
        int sectionLabelHeight = Math.max(24, style.smallFontSize + 12);
        int headerHeight = Math.max(style.headerHeight,
                Math.max(playerY + style.playerFontSize / 3, badgeY + style.badgeHeight) + sectionLabelHeight + 16);
        int footerHeight = Math.max(style.footerHeight,
                Math.max(style.footerOffsetY, style.smallFontSize + 26) + style.smallFontSize * 2 + 12);
        int hotbarGap = Math.max(style.hotbarGap, style.smallFontSize + 8);
        int gridWidth = slot * 9 + gap * 8;
        int gridHeight = slot * 4 + gap * 3 + hotbarGap + style.smallFontSize + 8;
        int equipmentHeight = slot * 5 + gap * 4;
        int contentHeight = Math.max(gridHeight, equipmentHeight);
        int equipmentWidth = Math.max(
                slot + style.equipmentExtraWidth,
                style.equipmentIconOffset + slot);
        int requiredWidth = padding * 2 + gridWidth + style.gridEquipmentGap + equipmentWidth;
        int width = Math.max(settings.getWidth(), requiredWidth);
        int height = headerHeight + contentHeight + footerHeight + padding;
        int left = (width - requiredWidth) / 2 + padding;

        int slotY(int row) {
            return headerHeight + row * (slot + gap) + (row == 3 ? hotbarGap : 0);
        }
    }

    private void paintBase(Graphics2D graphics, Layout layout) {
        configureGraphics(graphics);
        paintBackground(graphics, layout.width, layout.height);
        int equipmentX = layout.left + layout.gridWidth + style.gridEquipmentGap;
        int inset = Math.min(10, style.gridEquipmentGap / 3);
        int trayY = layout.headerHeight - layout.sectionLabelHeight;
        int trayHeight = layout.contentHeight + layout.sectionLabelHeight + 8;
        NativeImageDrawing.surface(graphics, layout.left - inset, trayY, layout.gridWidth + inset * 2,
                trayHeight, style.cardRadius / 2, NativeImageDrawing.alpha(style.slotBackgroundColor, 60),
                NativeImageDrawing.alpha(style.slotBorderColor, 40), NativeImageDrawing.alpha(style.cardShadowColor, 0));
        NativeImageDrawing.surface(graphics, equipmentX - inset, trayY, layout.equipmentWidth + inset * 2,
                trayHeight, style.cardRadius / 2, NativeImageDrawing.alpha(style.slotBackgroundColor, 60),
                NativeImageDrawing.alpha(style.slotBorderColor, 40), NativeImageDrawing.alpha(style.cardShadowColor, 0));
        for (int index = 0; index < DISPLAY_SLOTS.length; index++) {
            paintSlotBackground(graphics, layout.left + (index % 9) * (layout.slot + layout.gap),
                    layout.slotY(index / 9), layout.slot);
        }
        Font smallFont = new Font(settings.getFontName(), Font.PLAIN, style.smallFontSize);
        graphics.setFont(smallFont);
        graphics.setColor(style.equipmentTitleColor);
        int labelOffset = Math.min(layout.sectionLabelHeight - style.smallFontSize,
                Math.max(8, style.equipmentTitleOffsetY));
        graphics.drawString(ellipsize(translations.get("inventory.storage-title", "主背包", "Inventory"),
                graphics.getFontMetrics(), layout.gridWidth), layout.left, layout.headerHeight - labelOffset);
        graphics.drawString(ellipsize(translations.get("inventory.hotbar-title", "快捷栏", "Hotbar"),
                graphics.getFontMetrics(), layout.gridWidth), layout.left, layout.slotY(3) - 7);
        graphics.drawString(ellipsize(translations.get("inventory.equipment-title"),
                graphics.getFontMetrics(), layout.equipmentWidth), equipmentX, layout.headerHeight - labelOffset);
        for (int column = 0; column < 9; column++) {
            String number = String.valueOf(column + 1);
            graphics.setColor(style.secondaryTextColor);
            graphics.drawString(number, layout.left + column * (layout.slot + layout.gap)
                    + (layout.slot - graphics.getFontMetrics().stringWidth(number)) / 2,
                    layout.slotY(3) + layout.slot + style.smallFontSize + 3);
        }
        for (int index = 0; index < EQUIPMENT_SLOTS.length; index++) {
            int y = layout.headerHeight + index * (layout.slot + layout.gap);
            graphics.setColor(style.secondaryTextColor);
            graphics.drawString(index < equipmentLabels.size() ? equipmentLabels.get(index)
                    : String.valueOf(index + 1), equipmentX, y + layout.slot / 2 + 5);
            paintSlotBackground(graphics, equipmentX + style.equipmentIconOffset, y, layout.slot);
        }
        graphics.setColor(style.cardBorderColor);
        graphics.drawLine(layout.padding, layout.headerHeight + layout.contentHeight + 16,
                layout.width - layout.padding, layout.headerHeight + layout.contentHeight + 16);
    }

    private byte[] renderUncached(InventorySnapshot snapshot, boolean live) throws Exception {
        Layout layout = new Layout();
        int slot = layout.slot;
        int gap = layout.gap;
        int padding = layout.padding;
        int gridWidth = layout.gridWidth;
        int contentHeight = layout.contentHeight;
        int width = layout.width;
        int height = layout.height;

        BufferedImage image = baseCache.copy("inventory", width, height, graphics -> paintBase(graphics, layout));
        Graphics2D graphics = image.createGraphics();
        try {
            configureGraphics(graphics);

            Font titleFont = new Font(settings.getFontName(), Font.BOLD, style.titleFontSize);
            Font playerFont = new Font(settings.getFontName(), Font.BOLD, style.playerFontSize);
            Font smallFont = new Font(settings.getFontName(), Font.PLAIN, style.smallFontSize);
            Font amountFont = new Font(settings.getFontName(), Font.BOLD,
                    Math.max(style.minimumAmountFontSize, slot / 4));

            int left = layout.left;
            int top = layout.headerHeight;
            String badge = translations.get(live ? "inventory.live" : "inventory.snapshot");
            graphics.setFont(smallFont);
            int badgeWidth = graphics.getFontMetrics().stringWidth(badge) + style.badgeHorizontalPadding + 14;
            int badgeX = width - padding - badgeWidth;
            String title = settings.getTitle().replace("%player%", snapshot.getPlayerName());
            graphics.setFont(titleFont);
            graphics.setColor(style.titleColor);
            graphics.drawString(ellipsize(title, graphics.getFontMetrics(), badgeX - padding - 30),
                    padding + 14, layout.titleY);
            graphics.setColor(style.playerColor);
            graphics.fillRoundRect(padding, layout.titleY - style.titleFontSize + 2, 4, style.titleFontSize, 4, 4);

            graphics.setFont(playerFont);
            graphics.setColor(style.playerColor);
            graphics.drawString(ellipsize(snapshot.getPlayerName(), graphics.getFontMetrics(),
                    width - padding * 2 - 14), padding + 14, layout.playerY);

            graphics.setFont(smallFont);
            graphics.setColor(live ? style.liveBadgeColor : style.snapshotBadgeColor);
            graphics.fillRoundRect(badgeX, layout.badgeY, badgeWidth, style.badgeHeight,
                    style.statusBadgeRadius, style.statusBadgeRadius);
            graphics.setColor(style.badgeTextColor);
            int badgeTextY = layout.badgeY
                    + (style.badgeHeight - graphics.getFontMetrics().getHeight()) / 2
                    + graphics.getFontMetrics().getAscent();
            graphics.fillOval(badgeX + style.badgeHorizontalPadding / 2,
                    layout.badgeY + (style.badgeHeight - 6) / 2, 6, 6);
            graphics.drawString(badge, badgeX + style.badgeHorizontalPadding / 2 + 14, badgeTextY);

            graphics.setFont(amountFont);
            for (int index = 0; index < DISPLAY_SLOTS.length; index++) {
                int row = index / 9;
                int column = index % 9;
                int x = left + column * (slot + gap);
                int y = layout.slotY(row);
                paintSlot(graphics, x, y, slot, snapshot.getItem(DISPLAY_SLOTS[index]), amountFont);
            }

            int equipmentX = left + gridWidth + style.gridEquipmentGap;
            for (int index = 0; index < EQUIPMENT_SLOTS.length; index++) {
                int y = top + index * (slot + gap);
                paintSlot(graphics, equipmentX + style.equipmentIconOffset, y, slot,
                        snapshot.getItem(EQUIPMENT_SLOTS[index]), amountFont);
            }

            int footerY = top + contentHeight + Math.max(style.footerOffsetY, style.smallFontSize + 26);
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
            int availableWidth = width - padding * 2;
            if (graphics.getFontMetrics().stringWidth(summary) + timeWidth + style.gridEquipmentGap <= availableWidth) {
                graphics.drawString(summary, padding, footerY);
                graphics.drawString(timeText, width - padding - timeWidth, footerY);
            } else {
                graphics.drawString(ellipsize(summary, graphics.getFontMetrics(), availableWidth), padding, footerY);
                graphics.drawString(ellipsize(timeText, graphics.getFontMetrics(), availableWidth), padding,
                        footerY + graphics.getFontMetrics().getHeight() + 4);
            }
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
        if (item == null) {
            return;
        }
        if (item.isEnchanted()) {
            graphics.setColor(NativeImageDrawing.alpha(style.enchantedBorderColor, 22));
            graphics.fillRoundRect(x, y, size, size, style.slotRadius, style.slotRadius);
            graphics.setStroke(new BasicStroke(style.enchantedBorderWidth));
            graphics.setColor(style.enchantedBorderColor);
            graphics.drawRoundRect(x, y, size, size, style.slotRadius, style.slotRadius);
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
            while (graphics.getFont().getSize() > 8 && graphics.getFontMetrics().stringWidth(amount) > size - 12) {
                graphics.setFont(graphics.getFont().deriveFont((float) graphics.getFont().getSize() - 1));
            }
            FontMetrics metrics = graphics.getFontMetrics();
            int textX = x + size - metrics.stringWidth(amount) - 6;
            int textY = y + size - (item.hasDurability() ? 10 : 6);
            graphics.setColor(style.amountShadowColor);
            graphics.fillRoundRect(textX - 4, textY - metrics.getAscent() - 1,
                    metrics.stringWidth(amount) + 8, metrics.getHeight() + 2, 6, 6);
            graphics.setColor(style.amountTextColor);
            graphics.drawString(amount, textX, textY);
        }

        if (item.hasDurability()) {
            double remaining = 1.0D - (double) item.getDamage() / (double) item.getMaximumDurability();
            remaining = Math.max(0.0D, Math.min(1.0D, remaining));
            int barX = x + 6;
            int barY = y + size - 6;
            int barWidth = size - 12;
            graphics.setColor(style.durabilityBackgroundColor);
            graphics.fillRoundRect(barX, barY, barWidth, 3, 3, 3);
            graphics.setColor(durabilityColor(remaining));
            graphics.fillRoundRect(barX, barY, (int) Math.round(barWidth * remaining), 3, 3, 3);
        }
    }

    private void paintMissingTexture(Graphics2D graphics, int x, int y, int size) {
        graphics.setColor(NativeImageDrawing.alpha(style.missingDarkColor, 100));
        graphics.fillRoundRect(x + 2, y + 2, size - 4, size - 4, 8, 8);
        graphics.setColor(style.missingLightColor);
        graphics.setFont(new Font(settings.getFontName(), Font.BOLD, Math.max(12, size / 2)));
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString("?", x + (size - metrics.stringWidth("?")) / 2,
                y + (size - metrics.getHeight()) / 2 + metrics.getAscent());
    }

    private void paintSlotBackground(Graphics2D graphics, int x, int y, int size) {
        graphics.setPaint(new java.awt.GradientPaint(x, y, style.slotBackgroundColor,
                x, y + size, NativeImageDrawing.mix(style.slotBackgroundColor, Color.WHITE, 0.06F)));
        graphics.fillRoundRect(x, y, size, size, style.slotRadius, style.slotRadius);
        graphics.setStroke(new BasicStroke(style.normalBorderWidth));
        graphics.setColor(style.slotBorderColor);
        graphics.drawRoundRect(x, y, size, size, style.slotRadius, style.slotRadius);
        graphics.setColor(NativeImageDrawing.alpha(style.slotBorderColor, style.slotBorderColor.getAlpha() / 3));
        graphics.drawLine(x + 7, y + size - 2, x + size - 7, y + size - 2);
    }

    private Color durabilityColor(double remaining) {
        float hue = (float) (remaining / 3.0D);
        return Color.getHSBColor(hue, style.durabilitySaturation, style.durabilityBrightness);
    }

    private void paintBackground(Graphics2D graphics, int width, int height) {
        graphics.setPaint(new java.awt.GradientPaint(0, 0, style.backgroundColor,
                width, height, style.backgroundEndColor));
        graphics.fillRect(0, 0, width, height);
        NativeImageDrawing.glow(graphics, width, 0, Math.max(160, width / 2),
                NativeImageDrawing.alpha(style.playerColor, 24));
        NativeImageDrawing.surface(graphics, style.cardInset, style.cardInset,
                width - style.cardInset * 2, height - style.cardInset * 2, style.cardRadius,
                style.cardColor, style.cardBorderColor, style.cardShadowColor);
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
        if (maximumWidth <= 0) return "";
        if (metrics.stringWidth(text) <= maximumWidth) {
            return text;
        }
        String suffix = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > maximumWidth) {
            end = text.offsetByCodePoints(end, -1);
        }
        return end == 0 ? "" : text.substring(0, end) + suffix;
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
        private final int hotbarGap;
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
        private final Color backgroundEndColor;
        private final Color cardShadowColor;
        private final Color cardBorderColor;
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
            padding = template.getInt("inventory.layout.padding", 8, 160, 32);
            slotGap = template.getInt("inventory.layout.slot-gap", 0, 48, 7);
            hotbarGap = template.getInt("inventory.layout.hotbar-gap", 8, 80, 24);
            headerHeight = template.getInt("inventory.layout.header-height", 48, 240, 132);
            footerHeight = template.getInt("inventory.layout.footer-height", 24, 160, 68);
            gridEquipmentGap = template.getInt("inventory.layout.grid-equipment-gap", 0, 120, 32);
            equipmentExtraWidth = template.getInt("inventory.layout.equipment-extra-width", 32, 200, 64);
            equipmentIconOffset = template.getInt("inventory.layout.equipment-icon-offset", 0, 120, 28);
            cardInset = template.getInt("inventory.layout.card-inset", 0, 80, 12);
            titleY = template.getInt("inventory.layout.title-y", 16, 160, 54);
            playerY = template.getInt("inventory.layout.player-y", 24, 220, 82);
            badgeY = template.getInt("inventory.layout.badge-y", 0, 160, 30);
            badgeHeight = template.getInt("inventory.layout.badge-height", 16, 96, 28);
            badgeHorizontalPadding = template.getInt(
                    "inventory.layout.badge-horizontal-padding", 4, 100, 22);
            equipmentTitleOffsetY = template.getInt(
                    "inventory.layout.equipment-title-offset-y", 0, 80, 8);
            footerOffsetY = template.getInt("inventory.layout.footer-offset-y", 8, 120, 30);
            titleFontSize = fontSize(template, "title", 28);
            playerFontSize = fontSize(template, "player", 16);
            smallFontSize = fontSize(template, "small", 12);
            minimumAmountFontSize = fontSize(template, "minimum-amount", 12);
            cardRadius = radius(template, "card", 24);
            statusBadgeRadius = radius(template, "status-badge", 14);
            slotRadius = radius(template, "slot", 8);
            normalBorderWidth = template.getInt(
                    "inventory.strokes.normal-border-width-tenths", 1, 50, 10) / 10.0F;
            enchantedBorderWidth = template.getInt(
                    "inventory.strokes.enchanted-border-width-tenths", 1, 80, 16) / 10.0F;
            backgroundColor = color(template, "background", "#111820");
            backgroundEndColor = color(template, "background-end", "#18272B");
            cardShadowColor = color(template, "card-shadow", "#55000000");
            cardBorderColor = color(template, "card-border", "#364951");
            cardColor = color(template, "card", "#F01D2B34");
            titleColor = color(template, "title", "#F2F7F5");
            playerColor = color(template, "player", "#88D9BD");
            liveBadgeColor = color(template, "live-badge", "#2A5143");
            snapshotBadgeColor = color(template, "snapshot-badge", "#5B4932");
            badgeTextColor = color(template, "badge-text", "#EAF4EF");
            equipmentTitleColor = color(template, "equipment-title", "#B1C6CF");
            secondaryTextColor = color(template, "secondary-text", "#8FA6B0");
            slotBackgroundColor = color(template, "slot-background", "#14212B");
            slotBorderColor = color(template, "slot-border", "#415360");
            enchantedBorderColor = color(template, "enchanted-border", "#BFA0E5");
            amountShadowColor = color(template, "amount-shadow", "#D0101820");
            amountTextColor = color(template, "amount-text", "#F5F8FA");
            durabilityBackgroundColor = color(template, "durability-background", "#C0101820");
            missingLightColor = color(template, "missing-light", "#93A9B8");
            missingDarkColor = color(template, "missing-dark", "#263844");
            durabilitySaturation = template.getInt(
                    "inventory.durability.saturation-percent", 0, 100, 65) / 100.0F;
            durabilityBrightness = template.getInt(
                    "inventory.durability.brightness-percent", 0, 100, 85) / 100.0F;
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
