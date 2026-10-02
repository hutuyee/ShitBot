package haaa.shitbot.core.service;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.database.PlayerStats;
import haaa.shitbot.core.database.PlayerStatsRepository;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Resolves built-in profile fields while treating optional integrations as best effort. */
public final class PlayerProfileService {
    private final Settings settings;
    private final PlatformBridge platform;
    private final PlayerStatsRepository statsRepository;

    public PlayerProfileService(Settings settings,
                                PlatformBridge platform,
                                PlayerStatsRepository statsRepository) {
        this.settings = settings;
        this.platform = platform;
        this.statsRepository = statsRepository;
    }

    public CompletableFuture<Void> startSession(String playerName, String playerUuid) {
        return statsRepository.startSession(playerName, playerUuid);
    }

    public CompletableFuture<Void> endSession(String playerName, String playerUuid) {
        return statsRepository.endSession(playerName, playerUuid);
    }

    public CompletableFuture<PlayerProfile> find(final String playerName) {
        if (!TextUtil.isValidPlayerName(playerName)) {
            return FutureUtil.failedFuture(new IllegalArgumentException("Invalid player name"));
        }
        final String cleanName = playerName.trim();
        return statsRepository.findByPlayerName(cleanName).thenCompose(
                new java.util.function.Function<Optional<PlayerStats>, CompletableFuture<PlayerProfile>>() {
                    @Override
                    public CompletableFuture<PlayerProfile> apply(Optional<PlayerStats> optional) {
                        PlayerStats stats = optional.orElse(new PlayerStats(cleanName, "", 0L, null));
                        long total = stats.getTotalOnlineSeconds();
                        if (stats.getSessionStartedAt() != null) {
                            long elapsed = Math.max(0L,
                                    (System.currentTimeMillis() - stats.getSessionStartedAt().longValue()) / 1000L);
                            total += elapsed;
                        }
                        final long profileTotal = total;
                        return resolvePlaceholders(cleanName).handle(
                                new java.util.function.BiFunction<Map<String, String>, Throwable, PlayerProfile>() {
                                    @Override
                                    public PlayerProfile apply(Map<String, String> values, Throwable ignored) {
                                        String skin = skinUrl(cleanName);
                                        String group = value(values, "group", settings.getProfile().getPermissionGroupPlaceholder());
                                        String points = value(values, "points", settings.getProfile().getPointsPlaceholder());
                                        return new PlayerProfile(cleanName, skin, group, points, profileTotal,
                                                stats.getSessionStartedAt() != null);
                                    }
                                });
                    }
                });
    }

    public CompletableFuture<Map<String, Object>> provideData(String playerName) {
        return find(playerName).thenApply(new java.util.function.Function<PlayerProfile, Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(PlayerProfile profile) {
                Map<String, Object> values = new LinkedHashMap<String, Object>();
                values.put("player", profile.getPlayerName());
                values.put("name", profile.getPlayerName());
                values.put("skin-url", profile.getSkinUrl());
                values.put("skin", profile.getSkinUrl());
                values.put("permission-group", profile.getPermissionGroup());
                values.put("group", profile.getPermissionGroup());
                values.put("points", profile.getPoints());
                values.put("has-permission-group", Boolean.valueOf(!profile.getPermissionGroup().isEmpty()));
                values.put("has-points", Boolean.valueOf(!profile.getPoints().isEmpty()));
                values.put("total-online-seconds", Long.valueOf(profile.getTotalOnlineSeconds()));
                values.put("online-time-seconds", Long.valueOf(profile.getTotalOnlineSeconds()));
                values.put("total-online", formatDuration(profile.getTotalOnlineSeconds()));
                values.put("online-time", formatDuration(profile.getTotalOnlineSeconds()));
                values.put("online", Boolean.valueOf(profile.isOnline()));
                values.put("status", settings.getTranslations().get(profile.isOnline() ? "profile.online" : "profile.offline"));
                Map<String, Object> labels = new LinkedHashMap<String, Object>();
                for (String key : new String[]{"skin-title", "skin-unavailable", "permission-group", "points", "online-time", "footer"}) {
                    labels.put(key, settings.getTranslations().get("profile." + key));
                }
                values.put("labels", labels);
                return values;
            }
        });
    }

    private CompletableFuture<Map<String, String>> resolvePlaceholders(String playerName) {
        List<String> placeholders = new ArrayList<String>(2);
        if (!settings.getProfile().getPermissionGroupPlaceholder().isEmpty()) {
            placeholders.add(settings.getProfile().getPermissionGroupPlaceholder());
        }
        if (!settings.getProfile().getPointsPlaceholder().isEmpty()
                && !placeholders.contains(settings.getProfile().getPointsPlaceholder())) {
            placeholders.add(settings.getProfile().getPointsPlaceholder());
        }
        if (placeholders.isEmpty()) {
            return CompletableFuture.completedFuture(new LinkedHashMap<String, String>());
        }
        return platform.resolvePlaceholders(playerName, placeholders, settings.getProfile().getTargetServer());
    }

    private String skinUrl(String playerName) {
        try {
            return settings.getProfile().getSkinUrlTemplate().replace(
                    "%player%", URLEncoder.encode(playerName, StandardCharsets.UTF_8.name()));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String value(Map<String, String> values, String key, String placeholder) {
        if (values == null || placeholder == null || placeholder.trim().isEmpty()) {
            return "";
        }
        String value = values.get(placeholder);
        if (value == null || value.trim().isEmpty() || value.trim().equals(placeholder.trim())) {
            return "";
        }
        return value.trim();
    }

    public static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        long days = safe / 86400L;
        long hours = (safe % 86400L) / 3600L;
        long minutes = (safe % 3600L) / 60L;
        long remaining = safe % 60L;
        if (days > 0L) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0L) return hours + "h " + minutes + "m";
        if (minutes > 0L) return minutes + "m " + remaining + "s";
        return remaining + "s";
    }
}
