package haaa.shitbot.core.service;

import com.google.gson.JsonElement;
import haaa.shitbot.api.ImageRenderRequest;
import haaa.shitbot.api.ImageRenderResult;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.database.BindingRecord;
import haaa.shitbot.core.database.BindingRepository;
import haaa.shitbot.core.image.CustomImageService;
import haaa.shitbot.core.onebot.GroupMessage;
import haaa.shitbot.core.onebot.OneBotClient;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class EasyConsoleService {
    private final ConsoleSettings settings;
    private final Settings.Image imageSettings;
    private final Translations translations;
    private final PlatformBridge platform;
    private final BindingRepository repository;
    private final CustomImageService customImageService;
    private final OneBotClient client;
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<String, Long>();

    public EasyConsoleService(ConsoleSettings settings,
                              Settings.Image imageSettings,
                              Translations translations,
                              PlatformBridge platform,
                              BindingRepository repository,
                              CustomImageService customImageService,
                              OneBotClient client) {
        this.settings = settings;
        this.imageSettings = imageSettings;
        this.translations = translations;
        this.platform = platform;
        this.repository = repository;
        this.customImageService = customImageService;
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
        for (ConsoleSettings.ImageTemplateCommand command : settings.getImageTemplateCommands()) {
            CommandMatch match = matchCommand(raw, command.getAliases());
            if (command.isEnabled() && match != null) {
                if (command.getPlayerSource() == ConsoleSettings.PlayerSource.ARGUMENT
                        && !TextUtil.isValidPlayerName(match.arguments)) {
                    reply(message, command.getUsageMessage(), "", platform.getPlatformName(),
                            firstAlias(command.getAliases()), command.getTargetServer());
                } else if (!isCoolingDown(message, "image-template:" + command.getName(),
                        command.getCooldownSeconds())) {
                    executeImageTemplate(message, command, match.arguments);
                }
                return true;
            }
        }
        return false;
    }

    private void executeImageTemplate(final GroupMessage message,
                                      final ConsoleSettings.ImageTemplateCommand command,
                                      final String arguments) {
        resolveImagePlayers(message, command, arguments).thenCompose(
                new java.util.function.Function<PlayerSelection, CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> apply(final PlayerSelection selection) {
                        if (selection == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return authorizeImageCommand(command, selection).thenCompose(
                                new java.util.function.Function<ConsoleResult, CompletableFuture<Void>>() {
                                    @Override
                                    public CompletableFuture<Void> apply(ConsoleResult permissionResult) {
                                        if (permissionResult == null) {
                                            reply(message, settings.getUnavailableMessage(), "",
                                                    platform.getPlatformName(),
                                                    firstAlias(command.getAliases()), command.getTargetServer());
                                            return CompletableFuture.completedFuture(null);
                                        }
                                        if (permissionResult.getStatus() == ConsoleResult.Status.NO_PERMISSION) {
                                            reply(message, settings.getNoPermissionMessage(),
                                                    permissionResult.getOutput(), permissionResult.getSource(),
                                                    firstAlias(command.getAliases()), command.getTargetServer());
                                            return CompletableFuture.completedFuture(null);
                                        }
                                        if (!permissionResult.isSuccess()) {
                                            reply(message, settings.getUnavailableMessage(),
                                                    permissionResult.getOutput(), permissionResult.getSource(),
                                                    firstAlias(command.getAliases()), command.getTargetServer());
                                            return CompletableFuture.completedFuture(null);
                                        }
                                        Map<String, Object> context = new LinkedHashMap<String, Object>();
                                        context.put("title", imageSettings.getTitle());
                                        context.put("server-name", imageSettings.getServerName());
                                        context.put("qq", String.valueOf(message.getUserId()));
                                        context.put("group", Long.valueOf(message.getGroupId()));
                                        context.put("sender", message.getSenderName());
                                        context.put("player", selection.playerName);
                                        context.put("players", selection.playerNames);
                                        context.put("server", command.getTargetServer());
                                        context.put("arguments", arguments == null ? "" : arguments);
                                        context.put("command", command.getName());
                                        context.put("platform", platform.getPlatformName());
                                        return customImageService.renderImage(
                                                command.getTemplateId(), ImageRenderRequest.of(context))
                                                .thenCompose(new java.util.function.Function<ImageRenderResult,
                                                        CompletableFuture<JsonElement>>() {
                                                    @Override
                                                    public CompletableFuture<JsonElement> apply(
                                                            ImageRenderResult result) {
                                                        return client.sendGroupImage(message.getGroupId(),
                                                                result.getBytes(),
                                                                result.getSuggestedFileName());
                                                    }
                                                }).thenApply(new java.util.function.Function<JsonElement, Void>() {
                                                    @Override
                                                    public Void apply(JsonElement ignored) {
                                                        return null;
                                                    }
                                                });
                                    }
                                });
                    }
                }).whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
                    @Override
                    public void accept(Void ignored, Throwable throwable) {
                        if (throwable == null) return;
                        Throwable cause = FutureUtil.unwrap(throwable);
                        platform.error("Custom image command failed: " + command.getName(), cause);
                        reply(message, command.getFailedMessage(), safeMessage(cause),
                                platform.getPlatformName(), firstAlias(command.getAliases()),
                                command.getTargetServer());
                    }
                });
    }

    private CompletableFuture<PlayerSelection> resolveImagePlayers(
            final GroupMessage message,
            final ConsoleSettings.ImageTemplateCommand command,
            final String arguments) {
        if (command.getPlayerSource() == ConsoleSettings.PlayerSource.NONE) {
            return CompletableFuture.completedFuture(new PlayerSelection(
                    "", Collections.<String>emptyList()));
        }
        return repository.findAllByQqId(String.valueOf(message.getUserId())).thenApply(
                new java.util.function.Function<List<BindingRecord>, PlayerSelection>() {
                    @Override
                    public PlayerSelection apply(List<BindingRecord> bindings) {
                        if (bindings == null || bindings.isEmpty()) {
                            reply(message, settings.getNotBoundMessage(), "", platform.getPlatformName(),
                                    firstAlias(command.getAliases()), command.getTargetServer());
                            return null;
                        }
                        BindingRecord selected = bindings.get(0);
                        if (command.getPlayerSource() == ConsoleSettings.PlayerSource.ARGUMENT) {
                            selected = null;
                            for (BindingRecord binding : bindings) {
                                if (binding != null && binding.getPlayerName() != null
                                        && binding.getPlayerName().equals(arguments)) {
                                    selected = binding;
                                    break;
                                }
                            }
                            if (selected == null) {
                                reply(message, command.getUsageMessage(), "", platform.getPlatformName(),
                                        firstAlias(command.getAliases()), command.getTargetServer());
                                return null;
                            }
                        }
                        String playerName = selected == null || selected.getPlayerName() == null
                                ? "" : selected.getPlayerName();
                        return new PlayerSelection(playerName,
                                playerName.isEmpty() ? Collections.<String>emptyList()
                                        : Collections.singletonList(playerName));
                    }
                });
    }

    private CompletableFuture<ConsoleResult> authorizeImageCommand(
            ConsoleSettings.ImageTemplateCommand command,
            PlayerSelection selection) {
        if (command.getPermission().isEmpty()) {
            return CompletableFuture.completedFuture(new ConsoleResult(
                    "", ConsoleResult.Status.SUCCESS, "", platform.getPlatformName()));
        }
        return platform.executeConsoleRequest(ConsoleRequest.permission(
                command.getPermission(), selection.playerNames,
                command.getTargetServer(), settings.getRequestTimeoutSeconds()));
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
                            reply(message, tps.getFailedMessage(),
                                    translations.get("console.messages.request-failed"),
                                    platform.getPlatformName(),
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
        text = TextUtil.replace(text, "%source%", source == null || source.isEmpty()
                ? translations.get("console.messages.unknown-source")
                : source);
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
        return clean.isEmpty() ? translations.get("console.messages.empty-result") : clean;
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
        return isCoolingDown(message, commandKey, settings.getCommandCooldownSeconds());
    }

    private boolean isCoolingDown(GroupMessage message, String commandKey, int seconds) {
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

    private CommandMatch matchCommand(String raw, List<String> aliases) {
        if (raw == null || aliases == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        String selected = null;
        for (String alias : aliases) {
            if (alias == null || alias.trim().isEmpty()) continue;
            String clean = alias.trim();
            String lower = clean.toLowerCase(Locale.ROOT);
            if ((normalized.equals(lower) || normalized.startsWith(lower + " "))
                    && (selected == null || clean.length() > selected.length())) {
                selected = clean;
            }
        }
        return selected == null ? null : new CommandMatch(raw.trim().substring(selected.length()).trim());
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) return "";
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message.trim();
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

    private static final class CommandMatch {
        private final String arguments;

        private CommandMatch(String arguments) {
            this.arguments = arguments == null ? "" : arguments;
        }
    }

    private static final class PlayerSelection {
        private final String playerName;
        private final List<String> playerNames;

        private PlayerSelection(String playerName, List<String> playerNames) {
            this.playerName = playerName == null ? "" : playerName;
            this.playerNames = playerNames == null
                    ? Collections.<String>emptyList() : playerNames;
        }
    }
}
