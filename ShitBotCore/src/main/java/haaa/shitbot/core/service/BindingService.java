package haaa.shitbot.core.service;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.database.BindResult;
import haaa.shitbot.core.database.BindingRecord;
import haaa.shitbot.core.database.BindingRepository;
import haaa.shitbot.core.database.IssuedBindCode;
import haaa.shitbot.core.util.TextUtil;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Business rules for login verification and QQ binding. */
public final class BindingService {
    private final Settings settings;
    private final BindingRepository repository;

    public BindingService(Settings settings, BindingRepository repository) {
        this.settings = settings;
        this.repository = repository;
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

    public CompletableFuture<Optional<BindingRecord>> findByQqId(String qqId) {
        return repository.findByQqId(qqId);
    }
}
