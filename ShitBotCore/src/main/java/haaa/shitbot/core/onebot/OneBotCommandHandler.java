package haaa.shitbot.core.onebot;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.database.BindResult;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.service.BindingService;
import haaa.shitbot.core.service.InventoryQueryResult;
import haaa.shitbot.core.service.InventoryService;
import haaa.shitbot.core.service.OnlineImageService;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Dispatches customizable QQ group commands. */
public final class OneBotCommandHandler {
    private final Settings settings;
    private final PlatformBridge platform;
    private final BindingService bindingService;
    private final OnlineImageService imageService;
    private final InventoryService inventoryService;
    private final OneBotClient client;
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<String, Long>();

    public OneBotCommandHandler(Settings settings,
                                PlatformBridge platform,
                                BindingService bindingService,
                                OnlineImageService imageService,
                                InventoryService inventoryService,
                                OneBotClient client) {
        this.settings = settings;
        this.platform = platform;
        this.bindingService = bindingService;
        this.imageService = imageService;
        this.inventoryService = inventoryService;
        this.client = client;
    }

    public boolean handle(GroupMessage message) {
        String raw = message.getRawMessage().trim();
        if (raw.isEmpty()) {
            return false;
        }

        Match bindMatch = matchPrefix(raw, settings.getOneBot().getBindCommand().getAliases());
        if (settings.getOneBot().getBindCommand().isEnabled() && bindMatch != null) {
            if (!isCoolingDown(message, "bind")) {
                handleBind(message, bindMatch.remaining);
            }
            return true;
        }

        if (settings.getOneBot().getOnlineImageCommand().isEnabled()
                && matchesExact(raw, settings.getOneBot().getOnlineImageCommand().getAliases())) {
            if (!isCoolingDown(message, "online")) {
                handleOnlineImage(message);
            }
            return true;
        }
        if (settings.getOneBot().getInventoryCommand().isEnabled()) {
            Match inventoryMatch = matchPrefix(raw, settings.getOneBot().getInventoryCommand().getAliases());
            if (inventoryMatch != null) {
                if (!inventoryMatch.remaining.isEmpty()) {
                    reply(message, settings.getOneBot().getInventoryCommand().getUsage(), null, null);
                } else if (!isCoolingDown(message, "inventory")) {
                    handleInventory(message);
                }
                return true;
            }
        }

        return false;
    }

    private void handleBind(final GroupMessage message, String arguments) {
        String[] parts = arguments.trim().split("\\s+");
        if (parts.length != 2 || !TextUtil.isValidPlayerName(parts[0])) {
            reply(message, settings.getMessages().getBindUsage(), null, null);
            return;
        }
        final String playerName = parts[0];
        final String code = parts[1];
        final String qqId = String.valueOf(message.getUserId());

        bindingService.bind(playerName, qqId, code).whenComplete(
                new java.util.function.BiConsumer<BindResult, Throwable>() {
                    @Override
                    public void accept(BindResult result, Throwable throwable) {
                        if (throwable != null) {
                            platform.error("QQ binding database operation failed", FutureUtil.unwrap(throwable));
                            reply(message, settings.getMessages().getBindDatabaseError(), playerName, qqId);
                            return;
                        }
                        String template;
                        switch (result.getStatus()) {
                            case SUCCESS:
                            case ALREADY_BOUND_SAME:
                                template = settings.getMessages().getBindSuccess();
                                break;
                            case INVALID_CODE:
                                template = settings.getMessages().getBindInvalid();
                                break;
                            case QQ_ALREADY_BOUND:
                                template = settings.getMessages().getBindQqAlreadyUsed();
                                break;
                            case QQ_BINDING_LIMIT_REACHED:
                                template = settings.getMessages().getBindQqLimitReached();
                                break;
                            case PLAYER_ALREADY_BOUND:
                                template = settings.getMessages().getBindPlayerAlreadyUsed();
                                break;
                            case EXPIRED_OR_MISSING:
                            case TOO_MANY_ATTEMPTS:
                                template = settings.getMessages().getBindExpired();
                                break;
                            case INVALID_INPUT:
                            default:
                                template = settings.getMessages().getBindUsage();
                                break;
                        }
                        reply(message, template, playerName, qqId);
                    }
                });
    }

    private void handleOnlineImage(final GroupMessage message) {
        imageService.renderOnlineImageAsync().thenCompose(
                new java.util.function.Function<byte[], java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>>() {
                    @Override
                    public java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> apply(byte[] bytes) {
                        return client.sendGroupImage(message.getGroupId(), bytes, settings.getImage().getOutputFile());
                    }
                }).exceptionally(new java.util.function.Function<Throwable, com.fasterxml.jackson.databind.JsonNode>() {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode apply(Throwable throwable) {
                        platform.error("Failed to render/send online image", FutureUtil.unwrap(throwable));
                        reply(message, settings.getMessages().getOnlineFailed(), null, null);
                        return null;
                    }
                });
    }

    private void handleInventory(final GroupMessage message) {
        final String qqId = String.valueOf(message.getUserId());
        inventoryService.queryForQq(qqId).thenCompose(
                new java.util.function.Function<InventoryQueryResult,
                        java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>>() {
                    @Override
                    public java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> apply(
                            InventoryQueryResult result) {
                        switch (result.getStatus()) {
                            case SUCCESS:
                                return client.sendGroupImage(message.getGroupId(), result.getImage(),
                                        settings.getInventory().getOutputFile());
                            case NOT_BOUND:
                                reply(message, settings.getMessages().getInventoryNotBound(), null, qqId);
                                break;
                            case NO_SNAPSHOT:
                                reply(message, settings.getMessages().getInventoryUnavailable(), null, qqId);
                                break;
                            case DISABLED:
                            default:
                                reply(message, settings.getMessages().getInventoryDisabled(), null, qqId);
                                break;
                        }
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                }).exceptionally(new java.util.function.Function<Throwable, com.fasterxml.jackson.databind.JsonNode>() {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode apply(Throwable throwable) {
                        platform.error("Failed to query/render/send inventory image", FutureUtil.unwrap(throwable));
                        reply(message, settings.getMessages().getInventoryFailed(), null, qqId);
                        return null;
                    }
                });
    }

    private void reply(GroupMessage message, String template, String playerName, String qqId) {
        String text = template == null ? "" : template;
        boolean templateRequestsAt = text.contains("%at%") || text.contains("%艾特%");
        text = text.replace("%at%", "").replace("%艾特%", "");
        text = TextUtil.replace(text, "%player%", playerName == null ? "" : playerName);
        text = TextUtil.replace(text, "%qq%", qqId == null ? String.valueOf(message.getUserId()) : qqId);
        text = TextUtil.replace(text, "%expire_minutes%", Integer.valueOf(settings.getBinding().getExpireMinutes()));
        text = TextUtil.replace(text, "%maximum_ids%", Integer.valueOf(settings.getBinding().getMaximumIdsPerQq()));
        Long at = templateRequestsAt || settings.getOneBot().isReplyAtSender()
                ? Long.valueOf(message.getUserId())
                : null;
        client.sendGroupText(message.getGroupId(), text, at).exceptionally(
                new java.util.function.Function<Throwable, com.fasterxml.jackson.databind.JsonNode>() {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode apply(Throwable throwable) {
                        platform.warn("Failed to send OneBot group reply: " + FutureUtil.unwrap(throwable).getMessage());
                        return null;
                    }
                });
    }

    private boolean isCoolingDown(GroupMessage message, String commandKey) {
        int seconds = settings.getOneBot().getCommandCooldownSeconds();
        if (seconds <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        String key = message.getGroupId() + ":" + message.getUserId() + ':' + commandKey;
        Long previous = cooldowns.put(key, Long.valueOf(now));
        if (cooldowns.size() > 4096) {
            cleanupCooldowns(now, seconds * 1000L);
        }
        return previous != null && now - previous.longValue() < seconds * 1000L;
    }

    private void cleanupCooldowns(long now, long ttl) {
        for (java.util.Map.Entry<String, Long> entry : cooldowns.entrySet()) {
            if (now - entry.getValue().longValue() > ttl * 2L) {
                cooldowns.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private Match matchPrefix(String raw, List<String> aliases) {
        if (aliases == null) {
            return null;
        }
        String lowerRaw = raw.toLowerCase(Locale.ROOT);
        for (String alias : aliases) {
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            String cleanAlias = alias.trim();
            String lowerAlias = cleanAlias.toLowerCase(Locale.ROOT);
            if (lowerRaw.equals(lowerAlias)) {
                return new Match("");
            }
            if (lowerRaw.startsWith(lowerAlias)) {
                int length = cleanAlias.length();
                if (raw.length() > length && Character.isWhitespace(raw.charAt(length))) {
                    return new Match(raw.substring(length).trim());
                }
            }
        }
        return null;
    }

    private boolean matchesExact(String raw, List<String> aliases) {
        if (aliases == null) {
            return false;
        }
        for (String alias : aliases) {
            if (alias != null && raw.equalsIgnoreCase(alias.trim())) {
                return true;
            }
        }
        return false;
    }

    private static final class Match {
        private final String remaining;

        private Match(String remaining) {
            this.remaining = remaining;
        }
    }
}
