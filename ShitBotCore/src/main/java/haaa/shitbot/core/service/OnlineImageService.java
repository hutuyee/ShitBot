package haaa.shitbot.core.service;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.HashUtil;
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
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Asynchronously renders and caches the online-player image. */
public final class OnlineImageService implements AutoCloseable {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int OUTER_MARGIN = 48;
    private static final int HEADER_HEIGHT = 154;
    private static final int FOOTER_HEIGHT = 66;
    private static final int SERVER_GAP = 20;
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final Color[] PLACEHOLDER_COLORS = new Color[]{
            new Color(73, 139, 255),
            new Color(92, 196, 164),
            new Color(166, 113, 244),
            new Color(238, 126, 133),
            new Color(239, 170, 76),
            new Color(75, 174, 205)
    };

    private final Settings.Image settings;
    private final PlatformBridge platform;
    private final ExecutorService imageExecutor;
    private final ExecutorService avatarExecutor;
    private final ConcurrentHashMap<String, AvatarEntry> avatarMemory = new ConcurrentHashMap<String, AvatarEntry>();
    private final ConcurrentHashMap<String, CompletableFuture<BufferedImage>> avatarRequests =
            new ConcurrentHashMap<String, CompletableFuture<BufferedImage>>();
    private CompletableFuture<byte[]> inFlight;
    private volatile byte[] cachedBytes;
    private volatile long cacheExpiresAt;

    public OnlineImageService(Settings.Image settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
        this.imageExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("shitbot-image", true));
        this.avatarExecutor = Executors.newFixedThreadPool(
                settings.getAvatarDownloadThreads(), new NamedThreadFactory("shitbot-avatar", true));
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
        Map<String, BufferedImage> avatars = loadAvatars(snapshot);
        RenderLayout layout = measureLayout(snapshot);

        BufferedImage image = new BufferedImage(settings.getWidth(), layout.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configureGraphics(graphics);
            paintBackground(graphics, settings.getWidth(), layout.height);
            paintHeader(graphics, layout.totalPlayers);

            int y = HEADER_HEIGHT;
            if (layout.servers.isEmpty()) {
                paintEmptyPanel(graphics, y, settings.getWidth() - OUTER_MARGIN * 2, 116);
            } else {
                for (ServerLayout server : layout.servers) {
                    paintServerPanel(graphics, server, y, avatars);
                    y += server.height + SERVER_GAP;
                }
            }
            paintFooter(graphics, layout.height);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(96 * 1024);
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG writer is available");
        }
        return output.toByteArray();
    }

    private RenderLayout measureLayout(Map<String, List<String>> snapshot) {
        BufferedImage measuringImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = measuringImage.createGraphics();
        try {
            configureGraphics(graphics);
            Font playerFont = font(Font.PLAIN, 18);
            graphics.setFont(playerFont);
            FontMetrics playerMetrics = graphics.getFontMetrics();
            int contentWidth = settings.getWidth() - OUTER_MARGIN * 2;
            int innerWidth = contentWidth - 52;
            int badgeHeight = Math.max(46, settings.getAvatarSize() + 10);
            int rowGap = 10;
            int columnGap = 12;

            List<ServerLayout> servers = new ArrayList<ServerLayout>();
            int totalPlayers = 0;
            int contentHeight = 0;
            for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
                List<BadgeLayout> badges = new ArrayList<BadgeLayout>();
                int cursorX = 0;
                int cursorY = 0;
                int inRow = 0;
                int rows = 1;
                for (String player : entry.getValue()) {
                    String displayName = fitPlayerName(playerMetrics, TextUtil.singleLine(player, 48), innerWidth);
                    int badgeWidth = settings.getAvatarSize() + 12 + playerMetrics.stringWidth(displayName) + 20;
                    badgeWidth = Math.max(settings.getAvatarSize() + 54, Math.min(innerWidth, badgeWidth));
                    boolean wrap = inRow >= settings.getPlayersPerRow()
                            || (inRow > 0 && cursorX + badgeWidth > innerWidth);
                    if (wrap) {
                        cursorX = 0;
                        cursorY += badgeHeight + rowGap;
                        inRow = 0;
                        rows++;
                    }
                    badges.add(new BadgeLayout(player, displayName, cursorX, cursorY, badgeWidth, badgeHeight));
                    cursorX += badgeWidth + columnGap;
                    inRow++;
                }
                int flowHeight = badges.isEmpty() ? badgeHeight : rows * badgeHeight + (rows - 1) * rowGap;
                int panelHeight = 78 + flowHeight + 24;
                servers.add(new ServerLayout(entry.getKey(), entry.getValue().size(), panelHeight, badges));
                totalPlayers += entry.getValue().size();
                contentHeight += panelHeight + SERVER_GAP;
            }
            if (!servers.isEmpty()) {
                contentHeight -= SERVER_GAP;
            } else {
                contentHeight = 116;
            }
            int height = Math.max(420, HEADER_HEIGHT + contentHeight + FOOTER_HEIGHT);
            return new RenderLayout(height, totalPlayers, servers);
        } finally {
            graphics.dispose();
        }
    }

    private String fitPlayerName(FontMetrics metrics, String value, int innerWidth) {
        String clean = value == null || value.trim().isEmpty() ? "未知玩家" : value.trim();
        int maximumTextWidth = Math.max(80, innerWidth - settings.getAvatarSize() - 32);
        if (metrics.stringWidth(clean) <= maximumTextWidth) {
            return clean;
        }
        String suffix = "…";
        int end = clean.length();
        while (end > 1 && metrics.stringWidth(clean.substring(0, end) + suffix) > maximumTextWidth) {
            end--;
        }
        return clean.substring(0, end) + suffix;
    }

    private void paintHeader(Graphics2D graphics, int totalPlayers) {
        int width = settings.getWidth();
        int x = OUTER_MARGIN;
        int y = 34;

        graphics.setComposite(AlphaComposite.SrcOver.derive(0.92f));
        graphics.setColor(new Color(255, 255, 255));
        graphics.fillRoundRect(x, y + 4, 8, 50, 8, 8);
        graphics.setComposite(AlphaComposite.SrcOver);

        graphics.setFont(font(Font.BOLD, 40));
        graphics.setColor(Color.WHITE);
        graphics.drawString(settings.getTitle(), x + 22, y + 39);

        graphics.setFont(font(Font.PLAIN, 18));
        graphics.setColor(new Color(211, 232, 249));
        graphics.drawString(settings.getServerName() + "  ·  " + platform.getPlatformName(), x + 22, y + 72);

        int cardWidth = 214;
        int cardHeight = 82;
        int cardX = width - OUTER_MARGIN - cardWidth;
        int cardY = 30;
        drawGlassCard(graphics, cardX, cardY, cardWidth, cardHeight, 24);

        graphics.setColor(new Color(104, 235, 181));
        graphics.fillOval(cardX + 22, cardY + 22, 10, 10);
        graphics.setFont(font(Font.BOLD, 13));
        graphics.setColor(new Color(208, 255, 237));
        graphics.drawString("ONLINE", cardX + 40, cardY + 32);

        graphics.setFont(font(Font.BOLD, 32));
        graphics.setColor(Color.WHITE);
        String count = String.valueOf(totalPlayers);
        FontMetrics countMetrics = graphics.getFontMetrics();
        graphics.drawString(count, cardX + cardWidth - 24 - countMetrics.stringWidth(count), cardY + 38);

        graphics.setFont(font(Font.PLAIN, 14));
        graphics.setColor(new Color(214, 235, 248));
        String label = "当前在线玩家";
        FontMetrics labelMetrics = graphics.getFontMetrics();
        graphics.drawString(label, cardX + cardWidth - 24 - labelMetrics.stringWidth(label), cardY + 64);
    }

    private void paintEmptyPanel(Graphics2D graphics, int y, int width, int height) {
        int x = OUTER_MARGIN;
        drawPanel(graphics, x, y, width, height);
        graphics.setColor(new Color(104, 235, 181));
        graphics.fillOval(x + 30, y + 34, 12, 12);
        graphics.setFont(font(Font.BOLD, 23));
        graphics.setColor(Color.WHITE);
        graphics.drawString("当前没有玩家在线", x + 58, y + 53);
        graphics.setFont(font(Font.PLAIN, 15));
        graphics.setColor(new Color(198, 221, 238));
        graphics.drawString("服务器正在等待玩家加入", x + 58, y + 80);
    }

    private void paintServerPanel(Graphics2D graphics,
                                  ServerLayout server,
                                  int y,
                                  Map<String, BufferedImage> avatars) {
        int x = OUTER_MARGIN;
        int width = settings.getWidth() - OUTER_MARGIN * 2;
        drawPanel(graphics, x, y, width, server.height);

        int iconX = x + 26;
        int iconY = y + 22;
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.23f));
        graphics.setColor(new Color(105, 238, 184));
        graphics.fillRoundRect(iconX, iconY, 34, 34, 12, 12);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setColor(new Color(131, 255, 203));
        graphics.fillOval(iconX + 12, iconY + 12, 10, 10);

        graphics.setFont(font(Font.BOLD, 23));
        graphics.setColor(Color.WHITE);
        graphics.drawString(TextUtil.singleLine(server.name, 48), x + 72, y + 46);

        String countText = server.playerCount + " 人在线";
        graphics.setFont(font(Font.PLAIN, 14));
        FontMetrics countMetrics = graphics.getFontMetrics();
        int countWidth = countMetrics.stringWidth(countText) + 26;
        int countX = x + width - 26 - countWidth;
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.13f));
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(countX, y + 23, countWidth, 31, 16, 16);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setColor(new Color(213, 236, 250));
        graphics.drawString(countText, countX + 13, y + 44);

        graphics.setComposite(AlphaComposite.SrcOver.derive(0.16f));
        graphics.setColor(Color.WHITE);
        graphics.drawLine(x + 26, y + 67, x + width - 26, y + 67);
        graphics.setComposite(AlphaComposite.SrcOver);

        int baseX = x + 26;
        int baseY = y + 82;
        graphics.setFont(font(Font.PLAIN, 18));
        for (BadgeLayout badge : server.badges) {
            int badgeX = baseX + badge.x;
            int badgeY = baseY + badge.y;
            paintPlayerBadge(graphics, badge, badgeX, badgeY, avatars.get(playerKey(badge.playerName)));
        }
    }

    private void paintPlayerBadge(Graphics2D graphics,
                                  BadgeLayout badge,
                                  int x,
                                  int y,
                                  BufferedImage avatar) {
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.12f));
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(x, y, badge.width, badge.height, 16, 16);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.17f));
        graphics.setColor(new Color(224, 242, 255));
        graphics.drawRoundRect(x, y, badge.width, badge.height, 16, 16);
        graphics.setComposite(AlphaComposite.SrcOver);

        int avatarSize = settings.getAvatarSize();
        int avatarX = x + 5;
        int avatarY = y + (badge.height - avatarSize) / 2;
        drawAvatar(graphics, badge.playerName, avatar, avatarX, avatarY, avatarSize);

        graphics.setFont(font(Font.PLAIN, 18));
        graphics.setColor(new Color(244, 249, 255));
        FontMetrics metrics = graphics.getFontMetrics();
        int textY = y + (badge.height - metrics.getHeight()) / 2 + metrics.getAscent();
        graphics.drawString(badge.displayName, avatarX + avatarSize + 11, textY);
    }

    private void drawAvatar(Graphics2D graphics,
                            String playerName,
                            BufferedImage avatar,
                            int x,
                            int y,
                            int size) {
        Shape previousClip = graphics.getClip();
        RoundRectangle2D clip = new RoundRectangle2D.Double(x, y, size, size, 11, 11);
        graphics.setClip(clip);
        if (avatar != null) {
            graphics.drawImage(avatar, x, y, size, size, null);
        } else {
            Color background = PLACEHOLDER_COLORS[Math.abs(playerKey(playerName).hashCode()) % PLACEHOLDER_COLORS.length];
            graphics.setColor(background);
            graphics.fillRect(x, y, size, size);
            String initial = playerName == null || playerName.isEmpty()
                    ? "?"
                    : playerName.substring(0, 1).toUpperCase(Locale.ROOT);
            graphics.setFont(font(Font.BOLD, Math.max(14, size / 2)));
            graphics.setColor(Color.WHITE);
            FontMetrics metrics = graphics.getFontMetrics();
            graphics.drawString(initial,
                    x + (size - metrics.stringWidth(initial)) / 2,
                    y + (size - metrics.getHeight()) / 2 + metrics.getAscent());
        }
        graphics.setClip(previousClip);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.32f));
        graphics.setColor(Color.WHITE);
        graphics.drawRoundRect(x, y, size, size, 11, 11);
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void paintFooter(Graphics2D graphics, int height) {
        int y = height - 30;
        graphics.setFont(font(Font.PLAIN, 14));
        graphics.setColor(new Color(185, 215, 235));
        graphics.drawString("生成时间  " + LocalDateTime.now().format(TIME_FORMAT), OUTER_MARGIN, y);

        String footer = "ShitBot  ·  OneBot 11";
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(footer, settings.getWidth() - OUTER_MARGIN - metrics.stringWidth(footer), y);
    }

    private void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setStroke(new BasicStroke(1.1f));
    }

    private void paintBackground(Graphics2D graphics, int width, int height) {
        graphics.setPaint(new GradientPaint(0, 0, new Color(19, 70, 139), width, height, new Color(24, 145, 133)));
        graphics.fillRect(0, 0, width, height);

        graphics.setPaint(new GradientPaint(0, 0, new Color(54, 142, 235, 120), width, 0,
                new Color(55, 204, 174, 68)));
        graphics.fillRect(0, 0, width, height);

        graphics.setComposite(AlphaComposite.SrcOver.derive(0.055f));
        graphics.setColor(Color.WHITE);
        graphics.fillOval(width - 270, -170, 430, 430);
        graphics.fillOval(-220, height - 260, 420, 420);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.035f));
        for (int x = 0; x < width; x += 44) {
            graphics.drawLine(x, 0, x, height);
        }
        for (int y = 0; y < height; y += 44) {
            graphics.drawLine(0, y, width, y);
        }
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void drawGlassCard(Graphics2D graphics, int x, int y, int width, int height, int radius) {
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.13f));
        graphics.setColor(Color.BLACK);
        graphics.fillRoundRect(x, y + 4, width, height, radius, radius);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.16f));
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(x, y, width, height, radius, radius);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.21f));
        graphics.setColor(new Color(226, 245, 255));
        graphics.drawRoundRect(x, y, width, height, radius, radius);
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void drawPanel(Graphics2D graphics, int x, int y, int width, int height) {
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.12f));
        graphics.setColor(Color.BLACK);
        graphics.fillRoundRect(x, y + 6, width, height, 28, 28);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.15f));
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(x, y, width, height, 28, 28);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.20f));
        graphics.setColor(new Color(225, 244, 255));
        graphics.drawRoundRect(x, y, width, height, 28, 28);
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
                    return (left == null ? "" : left).compareToIgnoreCase(right == null ? "" : right);
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

    private Map<String, BufferedImage> loadAvatars(Map<String, List<String>> snapshot) {
        if (!settings.isAvatarEnabled()) {
            return Collections.emptyMap();
        }
        Set<String> names = new LinkedHashSet<String>();
        for (List<String> players : snapshot.values()) {
            names.addAll(players);
        }
        if (names.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, CompletableFuture<BufferedImage>> futures = new LinkedHashMap<String, CompletableFuture<BufferedImage>>();
        Map<String, BufferedImage> result = new HashMap<String, BufferedImage>();
        int scheduledDownloads = 0;
        for (String player : names) {
            BufferedImage cached = findCachedAvatar(player, false);
            if (cached != null) {
                result.put(playerKey(player), cached);
            } else if (scheduledDownloads < settings.getAvatarMaximumDownloadsPerRender()) {
                futures.put(player, requestAvatar(player));
                scheduledDownloads++;
            } else {
                BufferedImage stale = findCachedAvatar(player, true);
                if (stale != null) {
                    result.put(playerKey(player), stale);
                }
            }
        }

        if (!futures.isEmpty() && settings.getAvatarWaitTimeoutMs() > 0) {
            CompletableFuture<?>[] all = futures.values().toArray(new CompletableFuture<?>[futures.size()]);
            try {
                CompletableFuture.allOf(all).get(settings.getAvatarWaitTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Slow downloads continue in the background and are used by the next generated image.
            } catch (Throwable ignored) {
                // Individual failures fall back to the deterministic placeholder avatar.
            }
        }

        for (Map.Entry<String, CompletableFuture<BufferedImage>> entry : futures.entrySet()) {
            BufferedImage image = null;
            try {
                image = entry.getValue().getNow(null);
            } catch (Throwable ignored) {
            }
            if (image == null) {
                image = findCachedAvatar(entry.getKey(), true);
            }
            if (image != null) {
                result.put(playerKey(entry.getKey()), image);
            }
        }
        return result;
    }

    private CompletableFuture<BufferedImage> requestAvatar(final String playerName) {
        final String key = playerKey(playerName);
        CompletableFuture<BufferedImage> existing = avatarRequests.get(key);
        if (existing != null) {
            return existing;
        }
        final CompletableFuture<BufferedImage> created = CompletableFuture.supplyAsync(
                new Supplier<BufferedImage>() {
                    @Override
                    public BufferedImage get() {
                        return downloadAvatar(playerName);
                    }
                }, avatarExecutor);
        CompletableFuture<BufferedImage> previous = avatarRequests.putIfAbsent(key, created);
        if (previous != null) {
            return previous;
        }
        created.whenComplete(new java.util.function.BiConsumer<BufferedImage, Throwable>() {
            @Override
            public void accept(BufferedImage image, Throwable throwable) {
                avatarRequests.remove(key, created);
            }
        });
        return created;
    }

    private BufferedImage downloadAvatar(String playerName) {
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(playerName, "UTF-8").replace("+", "%20");
            String urlValue = settings.getAvatarUrlTemplate().replace("%player%", encoded);
            connection = (HttpURLConnection) new URL(urlValue).openConnection();
            connection.setConnectTimeout(settings.getAvatarConnectTimeoutMs());
            connection.setReadTimeout(settings.getAvatarReadTimeoutMs());
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "ShitBot-Avatar/1.0");
            connection.setRequestProperty("Accept", "image/png,image/*;q=0.8");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return findCachedAvatar(playerName, true);
            }
            byte[] bytes;
            InputStream input = connection.getInputStream();
            try {
                bytes = readLimited(input, MAX_AVATAR_BYTES);
            } finally {
                input.close();
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return findCachedAvatar(playerName, true);
            }
            long now = System.currentTimeMillis();
            avatarMemory.put(playerKey(playerName), new AvatarEntry(image, now));
            writeAvatarCache(playerName, image);
            return image;
        } catch (Throwable ignored) {
            return findCachedAvatar(playerName, true);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("Avatar image is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private BufferedImage findCachedAvatar(String playerName, boolean allowStale) {
        String key = playerKey(playerName);
        long now = System.currentTimeMillis();
        long ttl = settings.getAvatarCacheMinutes() * 60_000L;
        AvatarEntry memory = avatarMemory.get(key);
        if (memory != null && (allowStale || now - memory.loadedAt <= ttl)) {
            return memory.image;
        }

        Path cacheFile = avatarCachePath(playerName);
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }
            long modified = Files.getLastModifiedTime(cacheFile).toMillis();
            if (!allowStale && now - modified > ttl) {
                return null;
            }
            BufferedImage image = ImageIO.read(cacheFile.toFile());
            if (image == null) {
                Files.deleteIfExists(cacheFile);
                return null;
            }
            avatarMemory.put(key, new AvatarEntry(image, modified));
            return image;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void writeAvatarCache(String playerName, BufferedImage image) {
        try {
            Path output = avatarCachePath(playerName);
            Files.createDirectories(output.getParent());
            Path temporary = output.resolveSibling(output.getFileName().toString() + ".tmp");
            ImageIO.write(image, "png", temporary.toFile());
            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Disk caching is optional; the in-memory cache still prevents repeated downloads this run.
        }
    }

    private Path avatarCachePath(String playerName) {
        String hash = HashUtil.sha256Hex("avatar", playerKey(playerName));
        return platform.getDataDirectory().resolve("images").resolve("avatar-cache").resolve(hash + ".png");
    }

    private String playerKey(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
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
        avatarExecutor.shutdownNow();
        try {
            if (!imageExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                imageExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            imageExecutor.shutdownNow();
        }
    }

    private static final class RenderLayout {
        private final int height;
        private final int totalPlayers;
        private final List<ServerLayout> servers;

        private RenderLayout(int height, int totalPlayers, List<ServerLayout> servers) {
            this.height = height;
            this.totalPlayers = totalPlayers;
            this.servers = servers;
        }
    }

    private static final class ServerLayout {
        private final String name;
        private final int playerCount;
        private final int height;
        private final List<BadgeLayout> badges;

        private ServerLayout(String name, int playerCount, int height, List<BadgeLayout> badges) {
            this.name = name;
            this.playerCount = playerCount;
            this.height = height;
            this.badges = badges;
        }
    }

    private static final class BadgeLayout {
        private final String playerName;
        private final String displayName;
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private BadgeLayout(String playerName, String displayName, int x, int y, int width, int height) {
            this.playerName = playerName;
            this.displayName = displayName;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private static final class AvatarEntry {
        private final BufferedImage image;
        private final long loadedAt;

        private AvatarEntry(BufferedImage image, long loadedAt) {
            this.image = image;
            this.loadedAt = loadedAt;
        }
    }
}
