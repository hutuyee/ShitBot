package haaa.shitbot.core.service;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.database.BindResult;
import haaa.shitbot.core.database.BindingRecord;
import haaa.shitbot.core.database.BindingRepository;
import haaa.shitbot.core.database.IssuedBindCode;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Business rules for login verification and QQ binding. */
public final class BindingService {
    private final Settings settings;
    private final BindingRepository repository;
    private final PlatformBridge platform;

    public BindingService(Settings settings, BindingRepository repository, PlatformBridge platform) {
        this.settings = settings;
        this.repository = repository;
        this.platform = platform;
    }

    public CompletableFuture<LoginDecision> checkLogin(final String playerName, final String playerUuid) {
        if (!settings.getBinding().isEnabled()) {
            return CompletableFuture.completedFuture(LoginDecision.allow());
        }
        if (!TextUtil.isValidPlayerName(playerName)) {
            return CompletableFuture.completedFuture(LoginDecision.deny(
                    TextUtil.color(settings.getMessages().getKickDatabaseUnavailable())));
        }

        return repository.findByPlayerName(playerName).thenCompose(
                new java.util.function.Function<Optional<BindingRecord>, CompletableFuture<LoginDecision>>() {
                    @Override
                    public CompletableFuture<LoginDecision> apply(Optional<BindingRecord> binding) {
                        if (binding.isPresent()) {
                            CompletableFuture<Void> uuidUpdate = repository.updateUuid(playerName, playerUuid);
                            return uuidUpdate.handle(new java.util.function.BiFunction<Void, Throwable, LoginDecision>() {
                                @Override
                                public LoginDecision apply(Void ignored, Throwable throwable) {
                                    return LoginDecision.allow();
                                }
                            });
                        }
                        return repository.issueCode(playerName).thenApply(
                                new java.util.function.Function<IssuedBindCode, LoginDecision>() {
                                    @Override
                                    public LoginDecision apply(IssuedBindCode issued) {
                                        String message = settings.getMessages().getKickUnbound();
                                        message = TextUtil.replace(message, "%player%", issued.getPlayerName());
                                        message = TextUtil.replace(message, "%code%", issued.getCode());
                                        message = TextUtil.replace(message, "%expire_minutes%",
                                                Integer.valueOf(settings.getBinding().getExpireMinutes()));
                                        return LoginDecision.deny(TextUtil.color(message));
                                    }
                                });
                    }
                }).exceptionally(new java.util.function.Function<Throwable, LoginDecision>() {
                    @Override
                    public LoginDecision apply(Throwable throwable) {
                        return LoginDecision.deny(TextUtil.color(settings.getMessages().getKickDatabaseUnavailable()));
                    }
                });
    }

    public CompletableFuture<BindResult> bind(String playerName, String qqId, String code) {
        return repository.bind(playerName, qqId, code);
    }

    public CompletableFuture<Optional<BindingRecord>> findByPlayerName(String playerName) {
        return repository.findByPlayerName(playerName);
    }

    /** Returns the login UUID recorded for this exact, case-sensitive binding name. */
    public CompletableFuture<Optional<UUID>> findUuidByPlayerName(String playerName) {
        return repository.findByPlayerName(playerName).thenApply(binding -> {
            if (!binding.isPresent() || binding.get().getPlayerUuid() == null) {
                return Optional.<UUID>empty();
            }
            try {
                return Optional.of(UUID.fromString(binding.get().getPlayerUuid()));
            } catch (IllegalArgumentException invalidUuid) {
                return Optional.<UUID>empty();
            }
        });
    }

    public CompletableFuture<Optional<BindingRecord>> findByQqId(String qqId) {
        return repository.findByQqId(qqId);
    }

    public CompletableFuture<List<BindingRecord>> findAllByQqId(String qqId) {
        return repository.findAllByQqId(qqId);
    }

    public CompletableFuture<List<BindingRecord>> listWhitelist(int offset, int limit) {
        return repository.listWhitelist(offset, limit);
    }

    public CompletableFuture<BindResult> addWhitelist(String playerName, String qqId) {
        return repository.addWhitelist(playerName, qqId);
    }

    public CompletableFuture<Optional<BindingRecord>> removeWhitelist(String playerName) {
        return repository.removeByPlayerName(playerName).thenApply(removed -> {
            if (removed.isPresent()) {
                try {
                    platform.disconnectPlayers(java.util.Collections.singletonList(removed.get().getPlayerName()),
                            TextUtil.color(settings.getMessages().getKickAfterUnbind()));
                } catch (Throwable throwable) {
                    platform.error("Failed to disconnect player after removing whitelist entry", throwable);
                }
            }
            return removed;
        });
    }

    public CompletableFuture<List<BindingRecord>> unbindByQqId(final String qqId) {
        return repository.removeByQqIdAndReturnBindings(qqId).thenApply(
                new java.util.function.Function<List<BindingRecord>, List<BindingRecord>>() {
                    @Override
                    public List<BindingRecord> apply(List<BindingRecord> bindings) {
                        if (bindings == null || bindings.isEmpty()) {
                            return bindings;
                        }
                        List<String> playerNames = new ArrayList<String>(bindings.size());
                        for (BindingRecord binding : bindings) {
                            if (binding != null && binding.getPlayerName() != null) {
                                playerNames.add(binding.getPlayerName());
                            }
                        }
                        if (!playerNames.isEmpty()) {
                            try {
                                platform.disconnectPlayers(
                                        playerNames,
                                        TextUtil.color(settings.getMessages().getKickAfterUnbind()));
                            } catch (Throwable throwable) {
                                platform.error("Failed to disconnect players after unbinding qq=" + qqId,
                                        throwable);
                            }
                        }
                        return bindings;
                    }
                });
    }
}
