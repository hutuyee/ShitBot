package haaa.shitbot.core.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ConsoleRequest {
    public enum Operation {
        COMMAND,
        TPS
    }

    private final String requestId;
    private final Operation operation;
    private final ConsoleSettings.Target target;
    private final String command;
    private final String permission;
    private final List<String> playerNames;
    private final String server;
    private final int captureSeconds;
    private final int timeoutSeconds;

    public ConsoleRequest(String requestId,
                          Operation operation,
                          ConsoleSettings.Target target,
                          String command,
                          String permission,
                          List<String> playerNames,
                          String server,
                          int captureSeconds,
                          int timeoutSeconds) {
        this.requestId = requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
        this.operation = operation == null ? Operation.COMMAND : operation;
        this.target = target == null ? ConsoleSettings.Target.BACKEND : target;
        this.command = command == null ? "" : command.trim();
        this.permission = permission == null ? "" : permission.trim();
        this.playerNames = playerNames == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(playerNames));
        this.server = server == null ? "" : server.trim();
        this.captureSeconds = clamp(captureSeconds, 1, 30, 5);
        this.timeoutSeconds = clamp(timeoutSeconds, 2, 60, 15);
    }

    public static ConsoleRequest command(ConsoleSettings.Shortcut shortcut,
                                           List<String> playerNames,
                                           int timeoutSeconds,
                                           String targetServer) {
        return new ConsoleRequest(
                null,
                Operation.COMMAND,
                shortcut.getTarget(),
                shortcut.getCommand(),
                shortcut.getPermission(),
                playerNames,
                targetServer == null || targetServer.trim().isEmpty()
                        ? shortcut.getServer() : targetServer,
                shortcut.getCaptureSeconds(),
                timeoutSeconds);
    }

    public static ConsoleRequest tps(ConsoleSettings.Tps tps,
                                     List<String> playerNames,
                                     int timeoutSeconds,
                                     String targetServer) {
        return new ConsoleRequest(
                null,
                Operation.TPS,
                ConsoleSettings.Target.BACKEND,
                "",
                tps.getPermission(),
                playerNames,
                targetServer == null || targetServer.trim().isEmpty()
                        ? tps.getServer() : targetServer,
                1,
                timeoutSeconds);
    }

    public String getRequestId() { return requestId; }
    public Operation getOperation() { return operation; }
    public ConsoleSettings.Target getTarget() { return target; }
    public String getCommand() { return command; }
    public String getPermission() { return permission; }
    public List<String> getPlayerNames() { return playerNames; }
    public String getServer() { return server; }
    public int getCaptureSeconds() { return captureSeconds; }
    public int getTimeoutSeconds() { return timeoutSeconds; }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
