package haaa.shitbot.core.inventory;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.Translations;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
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
        int gap = Math.max(4, slot / 10);
        int padding = Math.max(20, slot / 2);
        int headerHeight = 92;
        int footerHeight = 54;
        int gridWidth = slot * 9 + gap * 8;
        int gridHeight = slot * 4 + gap * 3;
        int equipmentHeight = slot * 5 + gap * 4;
        int contentHeight = Math.max(gridHeight, equipmentHeight);
        int equipmentWidth = slot + 72;
        int requiredWidth = padding * 2 + gridWidth + 24 + equipmentWidth;
        int width = Math.max(settings.getWidth(), requiredWidth);
        int height = headerHeight + contentHeight + footerHeight + padding;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configureGraphics(graphics);
            paintBackground(graphics, width, height);

            Font titleFont = new Font(settings.getFontName(), Font.BOLD, 28);
            Font playerFont = new Font(settings.getFontName(), Font.BOLD, 18);
            Font smallFont = new Font(settings.getFontName(), Font.PLAIN, 13);
            Font amountFont = new Font(settings.getFontName(), Font.BOLD, Math.max(12, slot / 4));

            int left = (width - requiredWidth) / 2 + padding;
            int top = headerHeight;
            String title = settings.getTitle().replace("%player%", snapshot.getPlayerName());
            graphics.setFont(titleFont);
            graphics.setColor(new Color(246, 248, 252));
            graphics.drawString(ellipsize(title, graphics.getFontMetrics(), width - padding * 2), padding, 38);

            graphics.setFont(playerFont);
            graphics.setColor(new Color(174, 214, 255));
            graphics.drawString(snapshot.getPlayerName(), padding, 66);

            String badge = translations.get(live ? "inventory.live" : "inventory.snapshot");
            graphics.setFont(smallFont);
            int badgeWidth = graphics.getFontMetrics().stringWidth(badge) + 20;
            int badgeX = width - padding - badgeWidth;
            graphics.setColor(live ? new Color(43, 137, 84) : new Color(128, 101, 46));
            graphics.fillRoundRect(badgeX, 24, badgeWidth, 28, 14, 14);
            graphics.setColor(Color.WHITE);
            graphics.drawString(badge, badgeX + 10, 43);

            graphics.setFont(amountFont);
            for (int index = 0; index < DISPLAY_SLOTS.length; index++) {
                int row = index / 9;
                int column = index % 9;
                int x = left + column * (slot + gap);
                int y = top + row * (slot + gap);
                paintSlot(graphics, x, y, slot, snapshot.getItem(DISPLAY_SLOTS[index]), amountFont);
            }

            int equipmentX = left + gridWidth + 24;
            graphics.setFont(smallFont);
            graphics.setColor(new Color(168, 177, 194));
            graphics.drawString(translations.get("inventory.equipment-title"), equipmentX, top - 10);
            for (int index = 0; index < EQUIPMENT_SLOTS.length; index++) {
                int y = top + index * (slot + gap);
                graphics.setFont(smallFont);
                graphics.setColor(new Color(151, 160, 178));
                String equipmentLabel = index < equipmentLabels.size()
                        ? equipmentLabels.get(index)
                        : String.valueOf(index + 1);
                graphics.drawString(equipmentLabel, equipmentX, y + slot / 2 + 5);
                paintSlot(graphics, equipmentX + 28, y, slot,
                        snapshot.getItem(EQUIPMENT_SLOTS[index]), amountFont);
            }

            int footerY = top + contentHeight + 30;
            graphics.setFont(smallFont);
            graphics.setColor(new Color(151, 160, 178));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new Date(snapshot.getCapturedAt()));
            String timeText = translations.format("inventory.data-time", "%time%", timestamp);
            int timeWidth = graphics.getFontMetrics().stringWidth(timeText);
            String summary = translations.format("inventory.summary",
                    "%occupied%", String.valueOf(snapshot.getOccupiedSlots()),
                    "%slots%", String.valueOf(InventorySnapshot.TOTAL_SLOTS),
                    "%items%", String.valueOf(snapshot.getTotalItemCount()),
                    "%server%", snapshot.getServerName());
            int summaryWidth = Math.max(80, width - padding * 2 - timeWidth - 24);
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
        graphics.setColor(new Color(20, 25, 35, 235));
        graphics.fillRoundRect(x, y, size, size, 10, 10);
        graphics.setStroke(new BasicStroke(item != null && item.isEnchanted() ? 2.0F : 1.0F));
        graphics.setColor(item != null && item.isEnchanted()
                ? new Color(151, 103, 255, 220)
                : new Color(73, 82, 101, 220));
        graphics.drawRoundRect(x, y, size, size, 10, 10);

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
            graphics.setColor(new Color(0, 0, 0, 190));
            graphics.drawString(amount, textX + 1, textY + 1);
            graphics.setColor(Color.WHITE);
            graphics.drawString(amount, textX, textY);
        }

        if (item.hasDurability()) {
            double remaining = 1.0D - (double) item.getDamage() / (double) item.getMaximumDurability();
            remaining = Math.max(0.0D, Math.min(1.0D, remaining));
            int barX = x + 4;
            int barY = y + size - 4;
            int barWidth = size - 8;
            graphics.setColor(new Color(0, 0, 0, 190));
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
                        ? new Color(241, 0, 241)
                        : new Color(24, 0, 24));
                int width = Math.min(cell, size - column * cell);
                int height = Math.min(cell, size - row * cell);
                graphics.fillRect(x + column * cell, y + row * cell, width, height);
            }
        }
    }

    private Color durabilityColor(double remaining) {
        float hue = (float) (remaining / 3.0D);
        return Color.getHSBColor(hue, 0.9F, 0.95F);
    }

    private void paintBackground(Graphics2D graphics, int width, int height) {
        graphics.setColor(new Color(10, 13, 19));
        graphics.fillRect(0, 0, width, height);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.65F));
        graphics.setColor(new Color(30, 40, 58));
        graphics.fillRoundRect(12, 12, width - 24, height - 24, 24, 24);
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
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
}
