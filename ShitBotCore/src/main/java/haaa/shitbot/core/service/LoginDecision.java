package haaa.shitbot.core.service;

public final class LoginDecision {
    private final boolean allowed;
    private final String message;

    private LoginDecision(boolean allowed, String message) {
        this.allowed = allowed;
        this.message = message;
    }

    public static LoginDecision allow() {
        return new LoginDecision(true, "");
    }

    public static LoginDecision deny(String message) {
        return new LoginDecision(false, message == null ? "" : message);
    }

    public boolean isAllowed() { return allowed; }
    public String getMessage() { return message; }
}
