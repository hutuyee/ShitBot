package haaa.shitbot.core.service;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.NamedThreadFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Renders the built-in two-column personal profile card. */
public final class PlayerProfileImageService implements AutoCloseable {
    private static final int MAX_SKIN_BYTES = 2 * 1024 * 1024;

    private final Settings.Profile settings;
    private final Translations translations;
    private final PlatformBridge platform;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("shitbot-profile-image", true));

    public PlayerProfileImageService(Settings.Profile settings,
                                     Translations translations,
                                     PlatformBridge platform) {
        this.settings = settings;
        this.translations = translations;
        this.platform = platform;
    }

    public CompletableFuture<byte[]> renderAsync(final PlayerProfile profile) {
        if (profile == null) {
            CompletableFuture<byte[]> failed = new CompletableFuture<byte[]>();
            failed.completeExceptionally(new IllegalArgumentException("Profile cannot be null"));
            return failed;
        }
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<byte[]>() {
            @Override
            public byte[] get() {
                try {
                    byte[] bytes = render(profile);
                    writeAtomically(bytes);
                    return bytes;
                } catch (IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }
        }, executor);
    }

    public Path getOutputPath() {
        return platform.getDataDirectory().resolve("images").resolve(settings.getOutputFile());
    }

    private byte[] render(PlayerProfile profile) throws IOException {
        BufferedImage image = new BufferedImage(settings.getWidth(), settings.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            paintBackground(graphics);
            int margin = 36;
            int gap = 24;
            int cardWidth = settings.getWidth() - margin * 2;
            int cardHeight = settings.getHeight() - margin * 2;
            int leftWidth = Math.max(260, Math.min(430, cardWidth / 3));
            int rightX = margin + leftWidth + gap;
            int rightWidth = cardWidth - leftWidth - gap;
            paintSkinCard(graphics, profile, margin, margin, leftWidth, cardHeight);
            paintDetailsCard(graphics, profile, rightX, margin, rightWidth, cardHeight);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(96 * 1024);
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG writer is available");
        }
        return output.toByteArray();
    }

    private void paintBackground(Graphics2D graphics) {
        graphics.setPaint(new GradientPaint(0, 0, new Color(18, 27, 42), settings.getWidth(),
                settings.getHeight(), new Color(42, 66, 78)));
        graphics.fillRect(0, 0, settings.getWidth(), settings.getHeight());
        graphics.setColor(new Color(122, 205, 184, 42));
        graphics.fillOval(settings.getWidth() - 260, -100, 360, 360);
        graphics.setColor(new Color(73, 137, 174, 35));
        graphics.fillOval(-140, settings.getHeight() - 180, 320, 320);
    }

    private void paintSkinCard(Graphics2D graphics,
                               PlayerProfile profile,
                               int x,
                               int y,
                               int width,
                               int height) throws IOException {
        fillRound(graphics, x, y, width, height, 28, new Color(17, 24, 36, 230));
        graphics.setColor(new Color(133, 220, 190));
        graphics.fillRoundRect(x + 24, y + 24, 5, 86, 3, 3);
        graphics.setColor(Color.WHITE);
        graphics.setFont(font(Font.BOLD, 27));
        graphics.drawString(text("profile.skin-title", "玩家皮肤"), x + 48, y + 61);
        graphics.setColor(new Color(164, 185, 197));
        graphics.setFont(font(Font.PLAIN, 16));
        graphics.drawString(profile.getPlayerName(), x + 48, y + 89);

        BufferedImage skin = downloadSkin(profile.getSkinUrl());
        int targetWidth = width - 70;
        int targetHeight = height - 145;
        if (skin != null) {
            double scale = Math.min(targetWidth / (double) skin.getWidth(),
                    targetHeight / (double) skin.getHeight());
            int drawWidth = Math.max(1, (int) Math.round(skin.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(skin.getHeight() * scale));
            graphics.drawImage(skin, x + (width - drawWidth) / 2, y + 115 + (targetHeight - drawHeight) / 2,
                    drawWidth, drawHeight, null);
        } else {
            fillRound(graphics, x + 45, y + 145, width - 90, Math.min(230, targetHeight), 20,
                    new Color(40, 57, 72));
            graphics.setColor(new Color(159, 190, 199));
            graphics.setFont(font(Font.PLAIN, 17));
            drawCentered(graphics, text("profile.skin-unavailable", "皮肤暂不可用"),
                    x + width / 2, y + 270);
        }
    }

    private void paintDetailsCard(Graphics2D graphics,
                                  PlayerProfile profile,
                                  int x,
                                  int y,
                                  int width,
                                  int height) {
        fillRound(graphics, x, y, width, height, 28, new Color(247, 250, 251, 245));
        graphics.setColor(new Color(36, 49, 61));
        graphics.setFont(font(Font.BOLD, 34));
        graphics.drawString(profile.getPlayerName(), x + 42, y + 70);
        graphics.setColor(new Color(94, 111, 123));
        graphics.setFont(font(Font.PLAIN, 17));
        graphics.drawString(profile.isOnline()
                        ? text("profile.online", "在线") : text("profile.offline", "离线"),
                x + 44, y + 101);
        graphics.setColor(new Color(213, 222, 226));
        graphics.fillRect(x + 42, y + 130, width - 84, 1);

        int rowY = y + 180;
        rowY = paintField(graphics, x, width, rowY,
                text("profile.permission-group", "权限组"), profile.getPermissionGroup());
        rowY = paintField(graphics, x, width, rowY,
                text("profile.points", "点券"), profile.getPoints());
        paintField(graphics, x, width, rowY,
                text("profile.online-time", "累计在线"),
                PlayerProfileService.formatDuration(profile.getTotalOnlineSeconds()));
        graphics.setColor(new Color(120, 137, 148));
        graphics.setFont(font(Font.PLAIN, 13));
        String footer = text("profile.footer", "ShitBot · Personal Profile");
        graphics.drawString(footer, x + 42, y + height - 30);
    }

    private int paintField(Graphics2D graphics,
                           int x,
                           int width,
                           int y,
                           String label,
                           String value) {
        if (value == null || value.trim().isEmpty()) {
            return y;
        }
        graphics.setColor(new Color(112, 130, 141));
        graphics.setFont(font(Font.PLAIN, 16));
        graphics.drawString(label, x + 42, y);
        graphics.setColor(new Color(36, 49, 61));
        graphics.setFont(font(Font.BOLD, 22));
        drawEllipsized(graphics, value, x + 42, y + 34, width - 84);
        return y + 92;
    }

    private BufferedImage downloadSkin(String skinUrl) {
        if (skinUrl == null || skinUrl.trim().isEmpty()) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(skinUrl).openConnection();
            connection.setConnectTimeout(settings.getSkinConnectTimeoutMs());
            connection.setReadTimeout(settings.getSkinReadTimeoutMs());
            connection.setRequestProperty("User-Agent", "ShitBot/PlayerProfile");
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return null;
            }
            int length = connection.getContentLength();
            if (length > MAX_SKIN_BYTES) return null;
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readLimited(input);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null || image.getWidth() > 2048 || image.getHeight() > 2048
                        || (long) image.getWidth() * image.getHeight() > 4L * 1024L * 1024L) {
                    return null;
                }
                return image;
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_SKIN_BYTES) throw new IOException("Skin image is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void writeAtomically(byte[] bytes) throws IOException {
        Path output = getOutputPath();
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName().toString() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private Font font(int style, int size) {
        return new Font(settings.getFontName(), style, size);
    }

    private void fillRound(Graphics2D graphics, int x, int y, int width, int height, int radius, Color color) {
        graphics.setColor(color);
        graphics.fill(new RoundRectangle2D.Double(x, y, width, height, radius, radius));
    }

    private void drawCentered(Graphics2D graphics, String text, int centerX, int baseline) {
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(text, centerX - metrics.stringWidth(text) / 2, baseline);
    }

    private void drawEllipsized(Graphics2D graphics, String value, int x, int baseline, int maxWidth) {
        String text = value == null ? "" : value;
        FontMetrics metrics = graphics.getFontMetrics();
        while (text.length() > 1 && metrics.stringWidth(text) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        if (!text.equals(value)) text = text.substring(0, Math.max(0, text.length() - 1)) + "…";
        graphics.drawString(text, x, baseline);
    }

    private String text(String key, String fallback) {
        String value = translations == null ? "" : translations.get(key, "");
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
