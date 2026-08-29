package haaa.shitbot.core.service;

import com.google.gson.JsonElement;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.database.BindingRecord;
import haaa.shitbot.core.database.BindingRepository;
import haaa.shitbot.core.onebot.GroupMessage;
import haaa.shitbot.core.onebot.OneBotClient;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class EasyConsoleService {
    private final ConsoleSettings settings;
    private final PlatformBridge platform;
    private final BindingRepository repository;
    private final OneBotClient client;
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<String, Long>();

    public EasyConsoleService(ConsoleSettings settings,
                              PlatformBridge platform,
                              BindingRepository repository,
                              OneBotClient client) {
        this.settings = settings;
        this.platform = platform;
        this.repository = repository;
        this.client = client;
        warnAboutUnboundShortcuts();
    }

    public boolean handle(final GroupMessage message) {
        if (!settings.isEnabled()) {
            return false;
        }
        String raw = message.getRawMessage().trim();
        TargetMatch tpsMatch = matchTarget(raw, settings.getTps().getAliases());
        if (settings.getTps().isEnabled() && tpsMatch != null) {
            if (!tpsMatch.isValid()) {
                reply(message, settings.getInvalidTargetMessage(), "", platform.getPlatformName(),
                        firstAlias(settings.getTps().getAliases()), "");
                return true;
            }
            if (!isCoolingDown(message, "tps")) {
                executeTps(message, tpsMatch.targetServer);
            }
            return true;
        }
        for (ConsoleSettings.Shortcut shortcut : settings.getShortcuts()) {
            TargetMatch shortcutMatch = matchTarget(raw, shortcut.getAliases());
            if (shortcut.isEnabled() && shortcutMatch != null) {
                if (!shortcutMatch.isValid()) {
                    reply(message, settings.getInvalidTargetMessage(), "", platform.getPlatformName(),
                            firstAlias(shortcut.getAliases()), "");
                    return true;
                }
                if (!isCoolingDown(message, "shortcut:" + shortcut.getName())) {
                    executeShortcut(message, shortcut, shortcutMatch.targetServer);
                }
                return true;
            }
        }
        return false;
    }

    private void executeShortcut(final GroupMessage message,
                                 final ConsoleSettings.Shortcut shortcut,
                                 final String targetServer) {
        resolvePlayers(message, shortcut.getPermission(), shortcut.isAllowUnbound()).thenCompose(
                new java.util.function.Function<List<String>, CompletableFuture<ConsoleResult>>() {
                    @Override
                    public CompletableFuture<ConsoleResult> apply(List<String> players) {
                        if (players == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return platform.executeConsoleRequest(ConsoleRequest.command(
                                shortcut, players, settings.getRequestTimeoutSeconds(), targetServer));
                    }
                }).whenComplete(new java.util.function.BiConsumer<ConsoleResult, Throwable>() {
                    @Override
                    public void accept(ConsoleResult result, Throwable throwable) {
                        if (throwable != null) {
                            platform.error("Console shortcut failed: " + shortcut.getName(),
                                    FutureUtil.unwrap(throwable));
                            reply(message, settings.getUnavailableMessage(), "", platform.getPlatformName(),
                                    firstAlias(shortcut.getAliases()), targetServer);
                            return;
                        }
                        if (result == null) {
                            return;
                        }
                        if (result.getStatus() == ConsoleResult.Status.NO_PERMISSION) {
                            reply(message, settings.getNoPermissionMessage(), result.getOutput(), result.getSource(),
                                    firstAlias(shortcut.getAliases()), targetServer);
                        } else if (result.getStatus() == ConsoleResult.Status.UNAVAILABLE) {
                            reply(message, settings.getUnavailableMessage(), result.getOutput(), result.getSource(),
                                    firstAlias(shortcut.getAliases()), targetServer);
                        } else if (result.getStatus() == ConsoleResult.Status.RESULT_TIMEOUT) {
                            reply(message, settings.getResultTimeoutMessage(), result.getOutput(), result.getSource(),
                                    firstAlias(shortcut.getAliases()), targetServer);
                        } else {
                            reply(message, result.isSuccess() ? shortcut.getSuccessMessage() : shortcut.getFailedMessage(),
                                    result.getOutput(), result.getSource(),
                                    firstAlias(shortcut.getAliases()), targetServer);
                        }
                    }
                });
    }

    private void executeTps(final GroupMessage message, final String targetServer) {
        final ConsoleSettings.Tps tps = settings.getTps();
        resolvePlayers(message, tps.getPermission(), true).thenCompose(
                new java.util.function.Function<List<String>, CompletableFuture<ConsoleResult>>() {
                    @Override
                    public CompletableFuture<ConsoleResult> apply(List<String> players) {
                        if (players == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return platform.executeConsoleRequest(ConsoleRequest.tps(
                                tps, players, settings.getRequestTimeoutSeconds(), targetServer));
                    }
                }).whenComplete(new java.util.function.BiConsumer<ConsoleResult, Throwable>() {
                    @Override
                    public void accept(ConsoleResult result, Throwable throwable) {
                        if (throwable != null) {
                            platform.warn("TPS request failed: " + FutureUtil.unwrap(throwable).getMessage());
                            reply(message, tps.getFailedMessage(), "请求执行失败", platform.getPlatformName(),
                                    firstAlias(tps.getAliases()), targetServer);
                            return;
                        }
                        if (result == null) {
                            return;
                        }
                        if (result.getStatus() == ConsoleResult.Status.NO_PERMISSION) {
                            reply(message, settings.getNoPermissionMessage(), result.getOutput(), result.getSource(),
                                    firstAlias(tps.getAliases()), targetServer);
                        } else if (result.getStatus() == ConsoleResult.Status.UNAVAILABLE) {
                            reply(message, settings.getUnavailableMessage(), result.getOutput(), result.getSource(),
                                    firstAlias(tps.getAliases()), targetServer);
                        } else if (result.getStatus() == ConsoleResult.Status.RESULT_TIMEOUT) {
                            reply(message, settings.getResultTimeoutMessage(), result.getOutput(), result.getSource(),
                                    firstAlias(tps.getAliases()), targetServer);
                        } else {
                            reply(message, result.isSuccess() ? tps.getSuccessMessage() : tps.getFailedMessage(),
                                    result.getOutput(), result.getSource(),
                                    firstAlias(tps.getAliases()), targetServer);
                        }
                    }
                });
    }

    private CompletableFuture<List<String>> resolvePlayers(final GroupMessage message,
                                                            String permission,
                                                            boolean allowUnbound) {
        if (allowUnbound && (permission == null || permission.trim().isEmpty())) {
            return CompletableFuture.completedFuture(new ArrayList<String>());
        }
        return repository.findAllByQqId(String.valueOf(message.getUserId())).thenApply(
                new java.util.function.Function<List<BindingRecord>, List<String>>() {
                    @Override
                    public List<String> apply(List<BindingRecord> bindings) {
                        if (bindings == null || bindings.isEmpty()) {
                            reply(message, settings.getNotBoundMessage(), "", platform.getPlatformName(), "", "");
                            return null;
                        }
                        List<String> players = new ArrayList<String>(bindings.size());
                        for (BindingRecord binding : bindings) {
                            if (binding != null && binding.getPlayerName() != null) {
                                players.add(binding.getPlayerName());
                            }
                        }
                        return players;
                    }
                });
    }

    private void warnAboutUnboundShortcuts() {
        for (ConsoleSettings.Shortcut shortcut : settings.getShortcuts()) {
            if (shortcut.isEnabled()
                    && shortcut.isAllowUnbound()
                    && shortcut.getPermission().isEmpty()) {
                platform.warn("Console shortcut '" + shortcut.getName()
                        + "' allows unbound QQ group members to execute a console command");
            }
        }
    }

    private void reply(final GroupMessage message,
                       String template,
                       String result,
                       String source,
                       String command,
                       String server) {
        String text = template == null ? "" : template;
        boolean atSender = text.contains("%at%") || text.contains("%艾特%");
        text = text.replace("%at%", "").replace("%艾特%", "");
        text = TextUtil.replace(text, "%result%", cleanResult(result));
        text = TextUtil.replace(text, "{result}", cleanResult(result));
        text = TextUtil.replace(text, "%source%", source == null || source.isEmpty() ? "未知" : source);
        text = TextUtil.replace(text, "%command%", command == null ? "" : command);
        text = TextUtil.replace(text, "%server%", server == null ? "" : server);
        client.sendGroupText(message.getGroupId(), text,
                atSender ? Long.valueOf(message.getUserId()) : null).exceptionally(
                new java.util.function.Function<Throwable, JsonElement>() {
                    @Override
                    public JsonElement apply(Throwable throwable) {
                        platform.warn("Failed to send console command reply: "
                                + FutureUtil.unwrap(throwable).getMessage());
                        return null;
                    }
                });
    }

    private String cleanResult(String result) {
        String clean = result == null ? "" : result.trim();
        return clean.isEmpty() ? "未返回日志" : clean;
    }

    private TargetMatch matchTarget(String raw, List<String> aliases) {
        if (raw == null || aliases == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        String matchedAlias = null;
        for (String alias : aliases) {
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            String cleanAlias = alias.trim();
            String normalizedAlias = cleanAlias.toLowerCase(Locale.ROOT);
            if ((normalized.equals(normalizedAlias)
                    || normalized.startsWith(normalizedAlias + " "))
                    && (matchedAlias == null || cleanAlias.length() > matchedAlias.length())) {
                matchedAlias = cleanAlias;
            }
        }
        if (matchedAlias == null) {
            return null;
        }
        String remaining = raw.trim().substring(matchedAlias.length()).trim();
        if (remaining.isEmpty()) {
            return new TargetMatch("");
        }
        if (remaining.length() > 64 || remaining.indexOf(' ') >= 0 || remaining.indexOf('\t') >= 0) {
            return TargetMatch.invalid();
        }
        return new TargetMatch(remaining);
    }

    private String firstAlias(List<String> aliases) {
        return aliases == null || aliases.isEmpty() ? "" : aliases.get(0);
    }

    private boolean isCoolingDown(GroupMessage message, String commandKey) {
        int seconds = settings.getCommandCooldownSeconds();
        if (seconds <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        long ttl = seconds * 1000L;
        String key = message.getGroupId() + ":" + message.getUserId() + ':' + commandKey;
        if (cooldowns.size() > 4096) {
            for (java.util.Map.Entry<String, Long> entry : cooldowns.entrySet()) {
                if (now - entry.getValue().longValue() > ttl * 2L) {
                    cooldowns.remove(entry.getKey(), entry.getValue());
                }
            }
        }
        Long current = Long.valueOf(now);
        while (true) {
            Long previous = cooldowns.putIfAbsent(key, current);
            if (previous == null) {
                return false;
            }
            if (now - previous.longValue() < ttl) {
                return true;
            }
            if (cooldowns.replace(key, previous, current)) {
                return false;
            }
        }
    }

    private static final class TargetMatch {
        private final String targetServer;
        private final boolean valid;

        private TargetMatch(String targetServer) {
            this(targetServer, true);
        }

        private TargetMatch(String targetServer, boolean valid) {
            this.targetServer = targetServer;
            this.valid = valid;
        }

        private static TargetMatch invalid() {
            return new TargetMatch("", false);
        }

        private boolean isValid() {
            return valid;
        }
    }
}
