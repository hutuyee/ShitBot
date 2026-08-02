package haaa.shitbot.core.onebot;

import haaa.shitbot.core.chat.ChatPart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GroupMessage {
    private final long groupId;
    private final long userId;
    private final long selfId;
    private final String rawMessage;
    private final String senderName;
    private final List<ChatPart> forwardingParts;

    public GroupMessage(long groupId,
                        long userId,
                        long selfId,
                        String rawMessage,
                        String senderName,
                        List<ChatPart> forwardingParts) {
        this.groupId = groupId;
        this.userId = userId;
        this.selfId = selfId;
        this.rawMessage = rawMessage == null ? "" : rawMessage;
        this.senderName = senderName == null || senderName.trim().isEmpty()
                ? String.valueOf(userId)
                : senderName.trim();
        if (forwardingParts == null || forwardingParts.isEmpty()) {
            this.forwardingParts = this.rawMessage.isEmpty()
                    ? Collections.<ChatPart>emptyList()
                    : Collections.singletonList(ChatPart.text(this.rawMessage));
        } else {
            this.forwardingParts = Collections.unmodifiableList(new ArrayList<ChatPart>(forwardingParts));
        }
    }

    public GroupMessage(long groupId, long userId, long selfId, String rawMessage, String senderName) {
        this(groupId, userId, selfId, rawMessage, senderName, null);
    }

    public long getGroupId() { return groupId; }
    public long getUserId() { return userId; }
    public long getSelfId() { return selfId; }
    public String getRawMessage() { return rawMessage; }
    public String getSenderName() { return senderName; }
    public List<ChatPart> getForwardingParts() { return forwardingParts; }

    public String getForwardingText() {
        StringBuilder builder = new StringBuilder();
        for (ChatPart part : forwardingParts) {
            if (part != null) {
                builder.append(part.getText());
            }
        }
        return builder.toString();
    }
}
