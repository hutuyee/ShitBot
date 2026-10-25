package haaa.shitbot.core.database;

public final class BindResult {
    public enum Status {
        SUCCESS,
        ALREADY_BOUND_SAME,
        INVALID_CODE,
        EXPIRED_OR_MISSING,
        TOO_MANY_ATTEMPTS,
        QQ_ALREADY_BOUND,
        QQ_BINDING_LIMIT_REACHED,
        PLAYER_ALREADY_BOUND,
        INVALID_INPUT
    }

    private final Status status;
    private final BindingRecord binding;

    private BindResult(Status status, BindingRecord binding) {
        this.status = status;
        this.binding = binding;
    }

    public static BindResult of(Status status) {
        return new BindResult(status, null);
    }

    public static BindResult success(Status status, BindingRecord binding) {
        return new BindResult(status, binding);
    }

    public Status getStatus() { return status; }
    public BindingRecord getBinding() { return binding; }
    public boolean isSuccess() { return status == Status.SUCCESS || status == Status.ALREADY_BOUND_SAME; }
}
