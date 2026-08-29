package haaa.shitbot.core.service;

import com.google.gson.JsonElement;
import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.onebot.GroupMessage;
import haaa.shitbot.core.onebot.OneBotClient;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
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

        List<ChatPart> content = extractForwardingParts(
                message.getForwardingParts(), settings.getForwarding().getGroupToGame());
        if (content == null || content.isEmpty()) {
            return;
        }

        String sender = sanitizeForGame(TextUtil.singleLine(message.getSenderName(), 64));
        List<ChatPart> output = new ArrayList<ChatPart>();
        output.add(ChatPart.text("§b[QQ] §f" + sender + "§7: §f"));
        int remaining = MAX_CHAT_LENGTH;
        for (ChatPart part : content) {
            if (part == null || remaining <= 0) {
                continue;
            }
            String cleanText = sanitizeForGame(part.getText());
            if (cleanText.length() > remaining) {
                cleanText = cleanText.substring(0, remaining);
            }
            remaining -= cleanText.length();
            if (cleanText.isEmpty()) {
                continue;
            }
            if (part.hasClickUrl()) {
                output.add(ChatPart.link("§d" + cleanText + "§f", part.getClickUrl(), part.getHoverText()));
            } else {
                output.add(ChatPart.text(cleanText));
            }
        }
        platform.broadcastRichMessage(output);
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
                    new java.util.function.Function<Throwable, JsonElement>() {
                        @Override
                        public JsonElement apply(Throwable throwable) {
                            platform.warn("Failed to forward Minecraft chat to QQ group: "
                                    + FutureUtil.unwrap(throwable).getMessage());
                            return null;
                        }
                    });
        }
    }

    private List<ChatPart> extractForwardingParts(List<ChatPart> source, Settings.Direction direction) {
        if (direction == null || !direction.isEnabled() || source == null || source.isEmpty()) {
            return null;
        }
        StringBuilder fullBuilder = new StringBuilder();
        for (ChatPart part : source) {
            if (part != null) {
                fullBuilder.append(part.getText());
            }
        }
        String full = fullBuilder.toString();
        int start = 0;
        if (direction.isRequirePrefix()) {
            String prefix = direction.getPrefix();
            if (!full.startsWith(prefix)) {
                return null;
            }
            start = prefix.length();
        }
        int end = full.length();
        while (start < end && Character.isWhitespace(full.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(full.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return null;
        }

        List<ChatPart> result = new ArrayList<ChatPart>();
        int offset = 0;
        for (ChatPart part : source) {
            if (part == null) {
                continue;
            }
            String value = part.getText();
            int partStart = offset;
            int partEnd = offset + value.length();
            int from = Math.max(start, partStart);
            int to = Math.min(end, partEnd);
            if (from < to) {
                result.add(new ChatPart(value.substring(from - partStart, to - partStart),
                        part.getClickUrl(), part.getHoverText()));
            }
            offset = partEnd;
        }
        return result.isEmpty() ? null : Collections.unmodifiableList(result);
    }

    private String sanitizeForGame(String value) {
        return value == null ? "" : value.replace('§', '&');
    }
}
