package haaa.shitbot.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.onebot.GroupMessage;
import haaa.shitbot.core.onebot.OneBotClient;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handles bidirectional Minecraft chat and QQ group forwarding. */
public final class MessageForwardingService {
    private static final int MAX_CHAT_LENGTH = 1024;

    private final Settings settings;
    private final PlatformBridge platform;
    private final OneBotClient oneBotClient;
    private final AtomicBoolean missingTargetGroupWarned = new AtomicBoolean();

    public MessageForwardingService(Settings settings, PlatformBridge platform, OneBotClient oneBotClient) {
        this.settings = settings;
        this.platform = platform;
        this.oneBotClient = oneBotClient;
    }

    public void handleGroupMessage(GroupMessage message) {
        List<Long> targetGroups = settings.getOneBot().getAllowedGroupIds();
        if (targetGroups.isEmpty() || !targetGroups.contains(Long.valueOf(message.getGroupId()))) {
            return;
        }
        String content = settings.getForwarding().getGroupToGame().extractContent(message.getRawMessage());
        if (content == null) {
            return;
        }
        final String sender = sanitizeForGame(TextUtil.singleLine(message.getSenderName(), 64));
        final String cleanContent = sanitizeForGame(TextUtil.singleLine(content, MAX_CHAT_LENGTH));
        if (cleanContent.isEmpty()) {
            return;
        }
        platform.broadcastMessage("§b[QQ] §f" + sender + ": " + cleanContent);
    }

    public void handleGameMessage(String playerName, String message) {
        String content = settings.getForwarding().getGameToGroup().extractContent(message);
        if (content == null) {
            return;
        }
        List<Long> groupIds = settings.getOneBot().getAllowedGroupIds();
        if (groupIds.isEmpty()) {
            if (missingTargetGroupWarned.compareAndSet(false, true)) {
                platform.warn("Game-to-group forwarding is enabled, but onebot.allowed-group-ids is empty; no target QQ group is available.");
            }
            return;
        }

        String player = TextUtil.singleLine(playerName, 64);
        String cleanContent = TextUtil.singleLine(content, MAX_CHAT_LENGTH);
        if (cleanContent.isEmpty()) {
            return;
        }
        final String output = "[MC] " + player + ": " + cleanContent;
        for (Long groupId : groupIds) {
            if (groupId == null || groupId.longValue() <= 0L) {
                continue;
            }
            oneBotClient.sendGroupText(groupId.longValue(), output, null).exceptionally(
                    new java.util.function.Function<Throwable, JsonNode>() {
                        @Override
                        public JsonNode apply(Throwable throwable) {
                            platform.warn("Failed to forward Minecraft chat to QQ group: "
                                    + FutureUtil.unwrap(throwable).getMessage());
                            return null;
                        }
                    });
        }
    }

    private String sanitizeForGame(String value) {
        return value == null ? "" : value.replace('§', '&');
    }
}
