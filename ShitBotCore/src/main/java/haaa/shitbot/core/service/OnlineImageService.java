package haaa.shitbot.core.service;

import haaa.shitbot.core.config.ImageTemplate;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.HashUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import haaa.shitbot.core.util.TextUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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
import java.util.Iterator;
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
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final int MAX_AVATAR_DIMENSION = 1024;
    private static final long MAX_AVATAR_PIXELS = 1024L * 1024L;
    private static final int MAX_AVATAR_MEMORY_ENTRIES = 256;
    private final Settings.Image settings;
    private final Translations translations;
    private final OnlineStyle style;
    private final PlatformBridge platform;
    private final ExecutorService imageExecutor;
    private final ExecutorService avatarExecutor;
    private final ConcurrentHashMap<String, AvatarEntry> avatarMemory = new ConcurrentHashMap<String, AvatarEntry>();
    private final ConcurrentHashMap<String, CompletableFuture<BufferedImage>> avatarRequests =
            new ConcurrentHashMap<String, CompletableFuture<BufferedImage>>();
    private CompletableFuture<byte[]> inFlight;
    private volatile byte[] cachedBytes;
    private volatile long cacheExpiresAt;

    public OnlineImageService(Settings.Image settings, Translations translations, PlatformBridge platform) {
        this.settings = settings;
        this.translations = translations;
        this.style = new OnlineStyle(settings.getTemplate());
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

            int y = style.headerHeight;
            if (layout.servers.isEmpty()) {
                paintEmptyPanel(graphics, y,
                        settings.getWidth() - style.outerMargin * 2,
                        style.emptyPanelHeight);
            } else {
                for (ServerLayout server : layout.servers) {
                    paintServerPanel(graphics, server, y, avatars);
                    y += server.height + style.serverGap;
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
            Font playerFont = font(Font.PLAIN, style.playerFontSize);
            graphics.setFont(playerFont);
            FontMetrics playerMetrics = graphics.getFontMetrics();
            int contentWidth = settings.getWidth() - style.outerMargin * 2;
            int innerWidth = contentWidth - style.panelHorizontalPadding * 2;
            int badgeHeight = Math.max(style.badgeMinimumHeight,
                    settings.getAvatarSize() + style.badgeAvatarGap);
            int rowGap = style.rowGap;
            int columnGap = style.columnGap;

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
                    int badgeWidth = settings.getAvatarSize() + style.badgeAvatarGap
                            + playerMetrics.stringWidth(displayName) + style.badgeHorizontalPadding;
                    badgeWidth = Math.max(settings.getAvatarSize() + style.badgeMinimumExtraWidth,
                            Math.min(innerWidth, badgeWidth));
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
                int panelHeight = style.panelHeaderHeight + flowHeight + style.panelBottomPadding;
                servers.add(new ServerLayout(entry.getKey(), entry.getValue().size(), panelHeight, badges));
                totalPlayers += entry.getValue().size();
                contentHeight += panelHeight + style.serverGap;
            }
            if (!servers.isEmpty()) {
                contentHeight -= style.serverGap;
            } else {
                contentHeight = style.emptyPanelHeight;
            }
            int height = Math.max(style.minimumHeight,
                    style.headerHeight + contentHeight + style.footerHeight);
            return new RenderLayout(height, totalPlayers, servers);
        } finally {
            graphics.dispose();
        }
    }

    private String fitPlayerName(FontMetrics metrics, String value, int innerWidth) {
        String clean = value == null || value.trim().isEmpty()
                ? translations.get("image.unknown-player")
                : value.trim();
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
        int x = style.outerMargin;
        int y = 34;

        graphics.setColor(style.headerAccentColor);
        graphics.fillRoundRect(x, y + 4,
                style.headerAccentWidth, style.headerAccentHeight,
                style.headerAccentWidth, style.headerAccentWidth);

        graphics.setFont(font(Font.BOLD, style.titleFontSize));
        graphics.setColor(style.titleColor);
        graphics.drawString(settings.getTitle(), x + 22, y + 39);

        graphics.setFont(font(Font.PLAIN, style.subtitleFontSize));
        graphics.setColor(style.subtitleColor);
        graphics.drawString(translations.format("image.subtitle",
                "%server%", settings.getServerName(),
                "%platform%", platform.getPlatformName()), x + 22, y + 72);

        int cardWidth = style.statusCardWidth;
        int cardHeight = style.statusCardHeight;
        int cardX = width - style.outerMargin - cardWidth;
        int cardY = 30;
        drawGlassCard(graphics, cardX, cardY, cardWidth, cardHeight, style.statusCardRadius);

        graphics.setColor(style.statusDotColor);
        graphics.fillOval(cardX + 22, cardY + 22, style.statusDotSize, style.statusDotSize);
        graphics.setFont(font(Font.BOLD, style.statusFontSize));
        graphics.setColor(style.statusTextColor);
        graphics.drawString(translations.get("image.online-status"), cardX + 40, cardY + 32);

        graphics.setFont(font(Font.BOLD, style.totalCountFontSize));
        graphics.setColor(style.totalCountColor);
        String count = String.valueOf(totalPlayers);
        FontMetrics countMetrics = graphics.getFontMetrics();
        graphics.drawString(count, cardX + cardWidth - 24 - countMetrics.stringWidth(count), cardY + 38);

        graphics.setFont(font(Font.PLAIN, style.totalLabelFontSize));
        graphics.setColor(style.totalLabelColor);
        String label = translations.get("image.current-players");
        FontMetrics labelMetrics = graphics.getFontMetrics();
        graphics.drawString(label, cardX + cardWidth - 24 - labelMetrics.stringWidth(label), cardY + 64);
    }

    private void paintEmptyPanel(Graphics2D graphics, int y, int width, int height) {
        int x = style.outerMargin;
        drawPanel(graphics, x, y, width, height);
        graphics.setColor(style.emptyDotColor);
        graphics.fillOval(x + 30, y + 34, 12, 12);
        graphics.setFont(font(Font.BOLD, style.emptyTitleFontSize));
        graphics.setColor(style.emptyTitleColor);
        graphics.drawString(translations.get("image.no-players"), x + 58, y + 53);
        graphics.setFont(font(Font.PLAIN, style.emptySubtitleFontSize));
        graphics.setColor(style.emptySubtitleColor);
        graphics.drawString(translations.get("image.waiting-for-players"), x + 58, y + 80);
    }

    private void paintServerPanel(Graphics2D graphics,
                                  ServerLayout server,
                                  int y,
                                  Map<String, BufferedImage> avatars) {
        int x = style.outerMargin;
        int width = settings.getWidth() - style.outerMargin * 2;
        drawPanel(graphics, x, y, width, server.height);

        int iconX = x + 26;
        int iconY = y + 22;
        graphics.setColor(style.serverIconColor);
        graphics.fillRoundRect(iconX, iconY,
                style.serverIconSize, style.serverIconSize,
                style.serverIconRadius, style.serverIconRadius);
        graphics.setColor(style.serverDotColor);
        int dotOffset = (style.serverIconSize - style.serverDotSize) / 2;
        graphics.fillOval(iconX + dotOffset, iconY + dotOffset,
                style.serverDotSize, style.serverDotSize);

        graphics.setFont(font(Font.BOLD, style.serverTitleFontSize));
        graphics.setColor(style.serverTitleColor);
        graphics.drawString(TextUtil.singleLine(server.name, 48), x + 72, y + 46);

        String countText = translations.format(
                "image.online-count", "%count%", String.valueOf(server.playerCount));
        graphics.setFont(font(Font.PLAIN, style.serverCountFontSize));
        FontMetrics countMetrics = graphics.getFontMetrics();
        int countWidth = countMetrics.stringWidth(countText) + 26;
        int countX = x + width - 26 - countWidth;
        graphics.setColor(style.serverCountBackgroundColor);
        graphics.fillRoundRect(countX, y + 23, countWidth, 31,
                style.countBadgeRadius, style.countBadgeRadius);
        graphics.setColor(style.serverCountTextColor);
        graphics.drawString(countText, countX + 13, y + 44);

        graphics.setColor(style.dividerColor);
        graphics.drawLine(x + 26, y + 67, x + width - 26, y + 67);

        int baseX = x + 26;
        int baseY = y + 82;
        graphics.setFont(font(Font.PLAIN, style.playerFontSize));
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
        graphics.setColor(style.playerBackgroundColor);
        graphics.fillRoundRect(x, y, badge.width, badge.height,
                style.playerBadgeRadius, style.playerBadgeRadius);
        graphics.setColor(style.playerBorderColor);
        graphics.drawRoundRect(x, y, badge.width, badge.height,
                style.playerBadgeRadius, style.playerBadgeRadius);

        int avatarSize = settings.getAvatarSize();
        int avatarX = x + 5;
        int avatarY = y + (badge.height - avatarSize) / 2;
        drawAvatar(graphics, badge.playerName, avatar, avatarX, avatarY, avatarSize);

        graphics.setFont(font(Font.PLAIN, style.playerFontSize));
        graphics.setColor(style.playerTextColor);
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
        RoundRectangle2D clip = new RoundRectangle2D.Double(
                x, y, size, size, style.avatarRadius, style.avatarRadius);
        graphics.setClip(clip);
        if (avatar != null) {
            graphics.drawImage(avatar, x, y, size, size, null);
        } else {
            int colorIndex = (playerKey(playerName).hashCode() & Integer.MAX_VALUE)
                    % style.placeholderColors.size();
            Color background = style.placeholderColors.get(colorIndex);
            graphics.setColor(background);
            graphics.fillRect(x, y, size, size);
            String initial = playerName == null || playerName.isEmpty()
                    ? "?"
                    : playerName.substring(0, 1).toUpperCase(Locale.ROOT);
            graphics.setFont(font(Font.BOLD, Math.max(style.placeholderMinimumFontSize, size / 2)));
            graphics.setColor(style.playerTextColor);
            FontMetrics metrics = graphics.getFontMetrics();
            graphics.drawString(initial,
                    x + (size - metrics.stringWidth(initial)) / 2,
                    y + (size - metrics.getHeight()) / 2 + metrics.getAscent());
        }
        graphics.setClip(previousClip);
        graphics.setColor(style.avatarBorderColor);
        graphics.drawRoundRect(x, y, size, size, style.avatarRadius, style.avatarRadius);
    }

    private void paintFooter(Graphics2D graphics, int height) {
        int y = height - 30;
        graphics.setFont(font(Font.PLAIN, style.footerFontSize));
        graphics.setColor(style.footerColor);
        graphics.drawString(translations.format("image.generated-at", "%time%",
                formatCurrentTime()), style.outerMargin, y);

        String footer = translations.get("image.footer-brand");
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(footer,
                settings.getWidth() - style.outerMargin - metrics.stringWidth(footer), y);
    }

    private void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setStroke(new BasicStroke(style.borderWidth));
    }

    private void paintBackground(Graphics2D graphics, int width, int height) {
        graphics.setPaint(new GradientPaint(
                0, 0, style.backgroundStartColor,
                width, height, style.backgroundEndColor));
        graphics.fillRect(0, 0, width, height);

        graphics.setPaint(new GradientPaint(
                0, 0, style.overlayStartColor,
                width, 0, style.overlayEndColor));
        graphics.fillRect(0, 0, width, height);

        graphics.setColor(style.decorationColor);
        graphics.fillOval(width - 270, -170, 430, 430);
        graphics.fillOval(-220, height - 260, 420, 420);
        graphics.setColor(style.gridColor);
        for (int x = 0; x < width; x += style.gridSize) {
            graphics.drawLine(x, 0, x, height);
        }
        for (int y = 0; y < height; y += style.gridSize) {
            graphics.drawLine(0, y, width, y);
        }
    }

    private void drawGlassCard(Graphics2D graphics, int x, int y, int width, int height, int radius) {
        graphics.setColor(style.cardShadowColor);
        graphics.fillRoundRect(x, y + 4, width, height, radius, radius);
        graphics.setColor(style.cardBackgroundColor);
        graphics.fillRoundRect(x, y, width, height, radius, radius);
        graphics.setColor(style.cardBorderColor);
        graphics.drawRoundRect(x, y, width, height, radius, radius);
    }

    private void drawPanel(Graphics2D graphics, int x, int y, int width, int height) {
        graphics.setColor(style.panelShadowColor);
        graphics.fillRoundRect(x, y + 6, width, height, style.panelRadius, style.panelRadius);
        graphics.setColor(style.panelBackgroundColor);
        graphics.fillRoundRect(x, y, width, height, style.panelRadius, style.panelRadius);
        graphics.setColor(style.panelBorderColor);
        graphics.drawRoundRect(x, y, width, height, style.panelRadius, style.panelRadius);
    }

    private String formatCurrentTime() {
        String pattern = translations.get("image.time-format");
        try {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException ignored) {
            return LocalDateTime.now().format(TIME_FORMAT);
        }
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
        return value == null || value.trim().isEmpty()
                ? translations.get("image.unknown-server")
                : value.trim();
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
            BufferedImage image = decodeAvatar(bytes);
            if (image == null) {
                return findCachedAvatar(playerName, true);
            }
            long now = System.currentTimeMillis();
            cacheAvatar(playerKey(playerName), image, now);
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
            if (Files.size(cacheFile) > MAX_AVATAR_BYTES) {
                Files.deleteIfExists(cacheFile);
                return null;
            }
            long modified = Files.getLastModifiedTime(cacheFile).toMillis();
            if (!allowStale && now - modified > ttl) {
                return null;
            }
            BufferedImage image;
            try {
                try (ImageInputStream input = ImageIO.createImageInputStream(cacheFile.toFile())) {
                    image = decodeAvatar(input);
                }
            } catch (IOException invalidImage) {
                Files.deleteIfExists(cacheFile);
                return null;
            }
            if (image == null) {
                Files.deleteIfExists(cacheFile);
                return null;
            }
            cacheAvatar(key, image, modified);
            return image;
        } catch (IOException ignored) {
            return null;
        }
    }

    private BufferedImage decodeAvatar(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            return decodeAvatar(input);
        }
    }

    private BufferedImage decodeAvatar(ImageInputStream input) throws IOException {
        if (input == null) {
            return null;
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            return null;
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0
                    || width > MAX_AVATAR_DIMENSION || height > MAX_AVATAR_DIMENSION
                    || (long) width * (long) height > MAX_AVATAR_PIXELS) {
                throw new IOException("Avatar dimensions are too large: " + width + "x" + height);
            }

            int targetSize = settings.getAvatarSize();
            int subsampling = Math.max(1, Math.min(width / targetSize, height / targetSize));
            ImageReadParam parameters = reader.getDefaultReadParam();
            if (subsampling > 1) {
                parameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
            }
            BufferedImage decoded = reader.read(0, parameters);
            if (decoded == null) {
                return null;
            }
            return scaleAvatar(decoded, targetSize);
        } finally {
            reader.dispose();
        }
    }

    private BufferedImage scaleAvatar(BufferedImage source, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            configureGraphics(graphics);
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private synchronized void cacheAvatar(String key, BufferedImage image, long loadedAt) {
        avatarMemory.put(key, new AvatarEntry(image, loadedAt));
        while (avatarMemory.size() > MAX_AVATAR_MEMORY_ENTRIES) {
            String oldestKey = null;
            AvatarEntry oldest = null;
            for (Map.Entry<String, AvatarEntry> entry : avatarMemory.entrySet()) {
                if (oldest == null || entry.getValue().loadedAt < oldest.loadedAt) {
                    oldestKey = entry.getKey();
                    oldest = entry.getValue();
                }
            }
            if (oldestKey == null || !avatarMemory.remove(oldestKey, oldest)) {
                break;
            }
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

    private static final class OnlineStyle {
        private final int outerMargin;
        private final int headerHeight;
        private final int footerHeight;
        private final int serverGap;
        private final int minimumHeight;
        private final int emptyPanelHeight;
        private final int panelHorizontalPadding;
        private final int panelHeaderHeight;
        private final int panelBottomPadding;
        private final int badgeMinimumHeight;
        private final int badgeHorizontalPadding;
        private final int badgeAvatarGap;
        private final int badgeMinimumExtraWidth;
        private final int rowGap;
        private final int columnGap;
        private final int gridSize;
        private final int headerAccentWidth;
        private final int headerAccentHeight;
        private final int statusCardWidth;
        private final int statusCardHeight;
        private final int serverIconSize;
        private final int serverDotSize;
        private final int statusDotSize;
        private final float borderWidth;
        private final int titleFontSize;
        private final int subtitleFontSize;
        private final int statusFontSize;
        private final int totalCountFontSize;
        private final int totalLabelFontSize;
        private final int emptyTitleFontSize;
        private final int emptySubtitleFontSize;
        private final int serverTitleFontSize;
        private final int serverCountFontSize;
        private final int playerFontSize;
        private final int placeholderMinimumFontSize;
        private final int footerFontSize;
        private final int statusCardRadius;
        private final int panelRadius;
        private final int serverIconRadius;
        private final int countBadgeRadius;
        private final int playerBadgeRadius;
        private final int avatarRadius;
        private final Color backgroundStartColor;
        private final Color backgroundEndColor;
        private final Color overlayStartColor;
        private final Color overlayEndColor;
        private final Color decorationColor;
        private final Color gridColor;
        private final Color headerAccentColor;
        private final Color titleColor;
        private final Color subtitleColor;
        private final Color statusDotColor;
        private final Color statusTextColor;
        private final Color totalCountColor;
        private final Color totalLabelColor;
        private final Color emptyDotColor;
        private final Color emptyTitleColor;
        private final Color emptySubtitleColor;
        private final Color serverIconColor;
        private final Color serverDotColor;
        private final Color serverTitleColor;
        private final Color serverCountBackgroundColor;
        private final Color serverCountTextColor;
        private final Color dividerColor;
        private final Color playerBackgroundColor;
        private final Color playerBorderColor;
        private final Color playerTextColor;
        private final Color avatarBorderColor;
        private final Color footerColor;
        private final Color cardShadowColor;
        private final Color cardBackgroundColor;
        private final Color cardBorderColor;
        private final Color panelShadowColor;
        private final Color panelBackgroundColor;
        private final Color panelBorderColor;
        private final List<Color> placeholderColors;

        private OnlineStyle(ImageTemplate template) {
            outerMargin = template.getInt("online.layout.outer-margin", 16, 160, 48);
            headerHeight = template.getInt("online.layout.header-height", 96, 320, 154);
            footerHeight = template.getInt("online.layout.footer-height", 36, 160, 66);
            serverGap = template.getInt("online.layout.server-gap", 0, 80, 20);
            minimumHeight = template.getInt("online.layout.minimum-height", 240, 4000, 420);
            emptyPanelHeight = template.getInt("online.layout.empty-panel-height", 64, 400, 116);
            panelHorizontalPadding = template.getInt("online.layout.panel-horizontal-padding", 8, 120, 26);
            panelHeaderHeight = template.getInt("online.layout.panel-header-height", 48, 240, 78);
            panelBottomPadding = template.getInt("online.layout.panel-bottom-padding", 0, 120, 24);
            badgeMinimumHeight = template.getInt("online.layout.badge-minimum-height", 24, 160, 46);
            badgeHorizontalPadding = template.getInt("online.layout.badge-horizontal-padding", 4, 120, 20);
            badgeAvatarGap = template.getInt("online.layout.badge-avatar-gap", 0, 60, 12);
            badgeMinimumExtraWidth = template.getInt(
                    "online.layout.badge-minimum-extra-width", 0, 240, 54);
            rowGap = template.getInt("online.layout.row-gap", 0, 60, 10);
            columnGap = template.getInt("online.layout.column-gap", 0, 60, 12);
            gridSize = template.getInt("online.layout.grid-size", 8, 240, 44);
            headerAccentWidth = template.getInt("online.sizes.header-accent-width", 2, 40, 8);
            headerAccentHeight = template.getInt("online.sizes.header-accent-height", 8, 160, 50);
            statusCardWidth = template.getInt("online.sizes.status-card-width", 120, 480, 214);
            statusCardHeight = template.getInt("online.sizes.status-card-height", 54, 200, 82);
            serverIconSize = template.getInt("online.sizes.server-icon-size", 16, 96, 34);
            serverDotSize = template.getInt("online.sizes.server-dot-size", 2, 48, 10);
            statusDotSize = template.getInt("online.sizes.status-dot-size", 2, 48, 10);
            borderWidth = template.getInt("online.sizes.border-width-tenths", 1, 50, 11) / 10.0F;
            titleFontSize = fontSize(template, "title", 40);
            subtitleFontSize = fontSize(template, "subtitle", 18);
            statusFontSize = fontSize(template, "status", 13);
            totalCountFontSize = fontSize(template, "total-count", 32);
            totalLabelFontSize = fontSize(template, "total-label", 14);
            emptyTitleFontSize = fontSize(template, "empty-title", 23);
            emptySubtitleFontSize = fontSize(template, "empty-subtitle", 15);
            serverTitleFontSize = fontSize(template, "server-title", 23);
            serverCountFontSize = fontSize(template, "server-count", 14);
            playerFontSize = fontSize(template, "player", 18);
            placeholderMinimumFontSize = fontSize(template, "placeholder-minimum", 14);
            footerFontSize = fontSize(template, "footer", 14);
            statusCardRadius = radius(template, "status-card", 24);
            panelRadius = radius(template, "panel", 28);
            serverIconRadius = radius(template, "server-icon", 12);
            countBadgeRadius = radius(template, "count-badge", 16);
            playerBadgeRadius = radius(template, "player-badge", 16);
            avatarRadius = radius(template, "avatar", 11);
            backgroundStartColor = color(template, "background-start", "#13468B");
            backgroundEndColor = color(template, "background-end", "#189185");
            overlayStartColor = color(template, "overlay-start", "#78368EEB");
            overlayEndColor = color(template, "overlay-end", "#4437CCAE");
            decorationColor = color(template, "decoration", "#0EFFFFFF");
            gridColor = color(template, "grid", "#09FFFFFF");
            headerAccentColor = color(template, "header-accent", "#EBFFFFFF");
            titleColor = color(template, "title", "#FFFFFFFF");
            subtitleColor = color(template, "subtitle", "#D3E8F9");
            statusDotColor = color(template, "status-dot", "#68EBB5");
            statusTextColor = color(template, "status-text", "#D0FFED");
            totalCountColor = color(template, "total-count", "#FFFFFFFF");
            totalLabelColor = color(template, "total-label", "#D6EBF8");
            emptyDotColor = color(template, "empty-dot", "#68EBB5");
            emptyTitleColor = color(template, "empty-title", "#FFFFFFFF");
            emptySubtitleColor = color(template, "empty-subtitle", "#C6DDEE");
            serverIconColor = color(template, "server-icon", "#3B69EEB8");
            serverDotColor = color(template, "server-dot", "#83FFCB");
            serverTitleColor = color(template, "server-title", "#FFFFFFFF");
            serverCountBackgroundColor = color(template, "server-count-background", "#21FFFFFF");
            serverCountTextColor = color(template, "server-count-text", "#D5ECFA");
            dividerColor = color(template, "divider", "#29FFFFFF");
            playerBackgroundColor = color(template, "player-background", "#1FFFFFFF");
            playerBorderColor = color(template, "player-border", "#2BE0F2FF");
            playerTextColor = color(template, "player-text", "#F4F9FF");
            avatarBorderColor = color(template, "avatar-border", "#52FFFFFF");
            footerColor = color(template, "footer", "#B9D7EB");
            cardShadowColor = color(template, "card-shadow", "#21000000");
            cardBackgroundColor = color(template, "card-background", "#29FFFFFF");
            cardBorderColor = color(template, "card-border", "#35E2F5FF");
            panelShadowColor = color(template, "panel-shadow", "#1F000000");
            panelBackgroundColor = color(template, "panel-background", "#26FFFFFF");
            panelBorderColor = color(template, "panel-border", "#33E1F4FF");
            placeholderColors = template.getColors("online.placeholder-colors",
                    "#498BFF", "#5CC4A4", "#A671F4", "#EE7E85", "#EFAA4C", "#4BAECD");
        }

        private static int fontSize(ImageTemplate template, String name, int fallback) {
            return template.getInt("online.fonts." + name, 8, 96, fallback);
        }

        private static int radius(ImageTemplate template, String name, int fallback) {
            return template.getInt("online.radii." + name, 0, 96, fallback);
        }

        private static Color color(ImageTemplate template, String name, String fallback) {
            return template.getColor("online.colors." + name, fallback);
        }
    }
}
