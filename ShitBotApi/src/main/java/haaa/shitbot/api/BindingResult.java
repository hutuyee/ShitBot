package haaa.shitbot.api;

import java.util.Optional;

/** Expected binding conflicts are results; database failures complete the future exceptionally. */
public final class BindingResult {
    public enum Status {
        SUCCESS, ALREADY_BOUND_SAME, INVALID_CODE, EXPIRED_OR_MISSING,
        TOO_MANY_ATTEMPTS, QQ_ALREADY_BOUND, QQ_BINDING_LIMIT_REACHED,
        PLAYER_ALREADY_BOUND, INVALID_INPUT
    }

    private final Status status;
    private final PlayerBinding binding;

    public BindingResult(Status status, PlayerBinding binding) {
        this.status = status;
        this.binding = binding;
    }

    public Status getStatus() { return status; }
    public Optional<PlayerBinding> getBinding() { return Optional.ofNullable(binding); }
    public boolean isSuccess() {
        return status == Status.SUCCESS || status == Status.ALREADY_BOUND_SAME;
    }
}
