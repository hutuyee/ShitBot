package haaa.shitbot.core.service;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.onebot.OneBotClient;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sends one configured startup notice after OneBot becomes available. */
public final class ServerStartupNotificationService {
    private final Settings.OneBot oneBotSettings;
    private final Settings.ServerStartupNotice noticeSettings;
    private final PlatformBridge platform;
    private final OneBotClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final Set<Long> deliveredGroups = Collections.synchronizedSet(new HashSet<Long>());
    private volatile String pendingServerName;

    public ServerStartupNotificationService(Settings settings,
                                            PlatformBridge platform,
                                            OneBotClient client) {
        this.oneBotSettings = settings.getOneBot();
        this.noticeSettings = settings.getOneBot().getServerStartupNotice();
        this.platform = platform;
        this.client = client;
    }

    public void request(String serverName) {
        if (!noticeSettings.isEnabled() || closed.get()) {
            return;
        }
        List<Long> groups = targetGroups();
        if (groups.isEmpty()) {
            platform.warn("Server startup notice is enabled, but onebot.allowed-group-ids is empty; "
                    + "there is no explicit group to notify.");
            return;
        }
        pendingServerName = cleanServerName(serverName);
        deliveredGroups.clear();
        flushIfConnected();
    }

    public void onConnected() {
        flushIfConnected();
    }

    public void close() {
        closed.set(true);
        pendingServerName = null;
        deliveredGroups.clear();
    }

    private void flushIfConnected() {
        final String serverName = pendingServerName;
        if (closed.get() || serverName == null || !client.isConnected()
                || !flushing.compareAndSet(false, true)) {
            return;
        }
        final List<Long> remaining = new ArrayList<Long>();
        final List<Long> targetGroups = targetGroups();
        for (Long groupId : targetGroups) {
            if (!deliveredGroups.contains(groupId)) {
                remaining.add(groupId);
            }
        }
        if (remaining.isEmpty()) {
            pendingServerName = null;
            flushing.set(false);
            return;
        }

        final String message = formatMessage(serverName);
        List<CompletableFuture<?>> sends = new ArrayList<CompletableFuture<?>>(remaining.size());
        for (final Long groupId : remaining) {
            CompletableFuture<?> send = client.sendGroupText(groupId.longValue(), message, null)
                    .thenRun(new Runnable() {
                        @Override
                        public void run() {
                            deliveredGroups.add(groupId);
                        }
                    });
            sends.add(send);
        }
        CompletableFuture.allOf(sends.toArray(new CompletableFuture<?>[sends.size()]))
                .whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
                    @Override
                    public void accept(Void ignored, Throwable throwable) {
                        if (throwable != null && !closed.get()) {
                            platform.warn("Unable to send server startup notice to every configured group: "
                                    + errorMessage(throwable));
                        }
                        if (deliveredGroups.containsAll(remaining)
                                && deliveredGroups.containsAll(targetGroups)) {
                            pendingServerName = null;
                            platform.info("Server startup notice sent for " + serverName + '.');
                        }
                        flushing.set(false);
                    }
                });
    }

    private String formatMessage(String serverName) {
        return noticeSettings.getMessage()
                .replace("%server%", serverName)
                .replace("%platform%", platform.getPlatformName());
    }

    private List<Long> targetGroups() {
        Set<Long> valid = new java.util.LinkedHashSet<Long>();
        for (Long groupId : oneBotSettings.getAllowedGroupIds()) {
            if (groupId != null && groupId.longValue() > 0L) {
                valid.add(groupId);
            }
        }
        return new ArrayList<Long>(valid);
    }

    private String cleanServerName(String serverName) {
        if (serverName == null || serverName.trim().isEmpty()) {
            return platform.getPlatformName();
        }
        return serverName.trim();
    }

    private String errorMessage(Throwable throwable) {
        Throwable cause = FutureUtil.unwrap(throwable);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }
}
