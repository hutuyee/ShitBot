package haaa.shitbot.core.service;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.NamedThreadFactory;
import haaa.shitbot.core.util.TextUtil;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Asynchronously renders and caches the online-player image. */
public final class OnlineImageService implements AutoCloseable {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Settings.Image settings;
    private final PlatformBridge platform;
    private final ExecutorService imageExecutor;
    private CompletableFuture<byte[]> inFlight;
    private volatile byte[] cachedBytes;
    private volatile long cacheExpiresAt;

    public OnlineImageService(Settings.Image settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
        this.imageExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("shitbot-image", true));
    }

    public synchronized CompletableFuture<byte[]> renderOnlineImageAsync() {
        long now = System.currentTimeMillis();
        byte[] currentCache = cachedBytes;
        if (currentCache != null && now < cacheExpiresAt) {
            return CompletableFuture.completedFuture(currentCache.clone());
        }

        CompletableFuture<byte[]> existing = inFlight;
        if (existing != null && !existing.isDone()) {
            return existing.thenApply(new java.util.function.Function<byte[], byte[]>() {
                @Override
                public byte[] apply(byte[] bytes) {
                    return bytes.clone();
                }
            });
        }

        final CompletableFuture<byte[]> created = platform.captureOnlinePlayers().thenApplyAsync(
                new java.util.function.Function<Map<String, List<String>>, byte[]>() {
                    @Override
                    public byte[] apply(Map<String, List<String>> snapshot) {
                        try {
                            byte[] bytes = render(snapshot == null
                                    ? Collections.<String, List<String>>emptyMap()
                                    : snapshot);
                            cachedBytes = bytes;
                            cacheExpiresAt = System.currentTimeMillis() + settings.getCacheSeconds() * 1000L;
                            writeAtomically(bytes);
                            return bytes;
                        } catch (IOException exception) {
                            throw new java.util.concurrent.CompletionException(exception);
                        }
                    }
                }, imageExecutor);

        inFlight = created;
        created.whenComplete(new java.util.function.BiConsumer<byte[], Throwable>() {
            @Override
            public void accept(byte[] bytes, Throwable throwable) {
                synchronized (OnlineImageService.this) {
                    if (inFlight == created) {
                        inFlight = null;
                    }
                }
            }
        });
        return created.thenApply(new java.util.function.Function<byte[], byte[]>() {
            @Override
            public byte[] apply(byte[] bytes) {
                return bytes.clone();
            }
        });
    }

    public Path getOutputPath() {
        return platform.getDataDirectory().resolve("images").resolve(settings.getOutputFile());
    }

    private byte[] render(Map<String, List<String>> originalSnapshot) throws IOException {
        Map<String, List<String>> snapshot = normalizeSnapshot(originalSnapshot);
        int totalPlayers = 0;
        int totalRows = 0;
        for (List<String> players : snapshot.values()) {
            totalPlayers += players.size();
            totalRows += Math.max(1, (players.size() + settings.getPlayersPerRow() - 1) / settings.getPlayersPerRow());
        }
        if (snapshot.isEmpty()) {
            totalRows = 1;
        }

        int headerHeight = 190;
        int serverHeaderHeight = 52;
        int playerRowHeight = 48;
        int serverGap = 22;
        int footerHeight = 70;
        int height = headerHeight + footerHeight + 40;
        if (snapshot.isEmpty()) {
            height += 120;
        } else {
            height += snapshot.size() * (serverHeaderHeight + serverGap + 20) + totalRows * playerRowHeight;
        }
        height = Math.max(420, height);

        BufferedImage image = new BufferedImage(settings.getWidth(), height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configureGraphics(graphics);
            paintBackground(graphics, settings.getWidth(), height);
            int contentWidth = settings.getWidth() - 96;
            int x = 48;
            int y = 42;

            Font titleFont = font(Font.BOLD, 40);
            Font subtitleFont = font(Font.PLAIN, 20);
            Font countFont = font(Font.BOLD, 30);
            Font serverFont = font(Font.BOLD, 24);
            Font playerFont = font(Font.PLAIN, 18);
            Font smallFont = font(Font.PLAIN, 15);

            graphics.setColor(Color.WHITE);
            graphics.setFont(titleFont);
            graphics.drawString(settings.getTitle(), x, y + 42);
            graphics.setFont(subtitleFont);
            graphics.setColor(new Color(225, 239, 255));
            graphics.drawString(settings.getServerName() + " · " + platform.getPlatformName(), x, y + 78);

            int badgeWidth = 250;
            int badgeX = settings.getWidth() - x - badgeWidth;
            graphics.setComposite(AlphaComposite.SrcOver.derive(0.20f));
            graphics.setColor(Color.WHITE);
            graphics.fill(new RoundRectangle2D.Double(badgeX, y, badgeWidth, 94, 28, 28));
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setFont(countFont);
            graphics.setColor(Color.WHITE);
            drawCentered(graphics, String.valueOf(totalPlayers), badgeX, y + 12, badgeWidth, 42);
            graphics.setFont(smallFont);
            drawCentered(graphics, "当前在线玩家", badgeX, y + 53, badgeWidth, 24);

            y += 132;
            if (snapshot.isEmpty()) {
                drawPanel(graphics, x, y, contentWidth, 110);
                graphics.setFont(serverFont);
                graphics.setColor(new Color(235, 245, 255));
                drawCentered(graphics, "当前没有玩家在线", x, y + 26, contentWidth, 52);
                y += 132;
            } else {
                for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
                    List<String> players = entry.getValue();
                    int rows = Math.max(1, (players.size() + settings.getPlayersPerRow() - 1) / settings.getPlayersPerRow());
                    int panelHeight = 68 + rows * playerRowHeight + 20;
                    drawPanel(graphics, x, y, contentWidth, panelHeight);

                    graphics.setFont(serverFont);
                    graphics.setColor(Color.WHITE);
                    graphics.drawString(TextUtil.singleLine(entry.getKey(), 48), x + 26, y + 38);
                    String countText = players.size() + " 人";
                    FontMetrics countMetrics = graphics.getFontMetrics();
                    graphics.setColor(new Color(189, 224, 255));
                    graphics.drawString(countText, x + contentWidth - 26 - countMetrics.stringWidth(countText), y + 38);

                    int gridY = y + 64;
                    int cellGap = 12;
                    int cellWidth = (contentWidth - 52 - (settings.getPlayersPerRow() - 1) * cellGap)
                            / settings.getPlayersPerRow();
                    graphics.setFont(playerFont);
                    for (int i = 0; i < players.size(); i++) {
                        int column = i % settings.getPlayersPerRow();
                        int row = i / settings.getPlayersPerRow();
                        int cellX = x + 26 + column * (cellWidth + cellGap);
                        int cellY = gridY + row * playerRowHeight;
                        graphics.setComposite(AlphaComposite.SrcOver.derive(0.13f));
                        graphics.setColor(Color.WHITE);
                        graphics.fillRoundRect(cellX, cellY, cellWidth, 34, 14, 14);
                        graphics.setComposite(AlphaComposite.SrcOver);
                        graphics.setColor(new Color(241, 248, 255));
                        String name = TextUtil.singleLine(players.get(i), 18);
                        graphics.drawString(name, cellX + 13, cellY + 23);
                    }
                    y += panelHeight + serverGap;
                }
            }

            graphics.setFont(smallFont);
            graphics.setColor(new Color(205, 226, 246));
            graphics.drawString("生成时间: " + LocalDateTime.now().format(TIME_FORMAT), x, height - 34);
            String footer = "ShitBot · OneBot 11";
            FontMetrics footerMetrics = graphics.getFontMetrics();
            graphics.drawString(footer, settings.getWidth() - x - footerMetrics.stringWidth(footer), height - 34);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG writer is available");
        }
        return output.toByteArray();
    }

    private Map<String, List<String>> normalizeSnapshot(Map<String, List<String>> source) {
        List<Map.Entry<String, List<String>>> entries = new ArrayList<Map.Entry<String, List<String>>>(source.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, List<String>>>() {
            @Override
            public int compare(Map.Entry<String, List<String>> left, Map.Entry<String, List<String>> right) {
                return normalizedServerName(left.getKey()).compareToIgnoreCase(normalizedServerName(right.getKey()));
            }
        });

        LinkedHashMap<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        int remaining = settings.getMaximumPlayers();
        for (Map.Entry<String, List<String>> entry : entries) {
            if (remaining <= 0) {
                break;
            }
            String serverName = normalizedServerName(entry.getKey());
            List<String> sourcePlayers = entry.getValue() == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(entry.getValue());
            Collections.sort(sourcePlayers, new Comparator<String>() {
                @Override
                public int compare(String left, String right) {
                    String leftValue = left == null ? "" : left;
                    String rightValue = right == null ? "" : right;
                    return leftValue.compareToIgnoreCase(rightValue);
                }
            });
            List<String> cleanPlayers = result.get(serverName);
            if (cleanPlayers == null) {
                cleanPlayers = new ArrayList<String>();
                result.put(serverName, cleanPlayers);
            }
            for (String player : sourcePlayers) {
                if (player != null && !player.trim().isEmpty() && remaining > 0) {
                    cleanPlayers.add(player.trim());
                    remaining--;
                }
            }
        }
        return result;
    }

    private String normalizedServerName(String value) {
        return value == null || value.trim().isEmpty() ? "未知服务器" : value.trim();
    }

    private void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setStroke(new BasicStroke(1.2f));
    }

    private void paintBackground(Graphics2D graphics, int width, int height) {
        graphics.setPaint(new GradientPaint(0, 0, new Color(24, 108, 196), width, height, new Color(62, 182, 156)));
        graphics.fillRect(0, 0, width, height);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.08f));
        graphics.setColor(Color.WHITE);
        graphics.fillOval(width - 280, -120, 430, 430);
        graphics.fillOval(-180, height - 260, 420, 420);
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void drawPanel(Graphics2D graphics, int x, int y, int width, int height) {
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.16f));
        graphics.setColor(Color.WHITE);
        graphics.fill(new RoundRectangle2D.Double(x, y, width, height, 28, 28));
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.24f));
        graphics.setColor(new Color(220, 240, 255));
        graphics.draw(new RoundRectangle2D.Double(x, y, width, height, 28, 28));
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private Font font(int style, int size) {
        Font configured = new Font(settings.getFontName(), style, size);
        if (configured.getFamily().equalsIgnoreCase("Dialog")
                && !"Dialog".equalsIgnoreCase(settings.getFontName())) {
            return new Font("Dialog", style, size);
        }
        return configured;
    }

    private void drawCentered(Graphics2D graphics, String text, int x, int y, int width, int height) {
        FontMetrics metrics = graphics.getFontMetrics();
        int drawX = x + (width - metrics.stringWidth(text)) / 2;
        int drawY = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
        graphics.drawString(text, drawX, drawY);
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

    @Override
    public void close() {
        imageExecutor.shutdown();
        try {
            if (!imageExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                imageExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            imageExecutor.shutdownNow();
        }
    }
}
