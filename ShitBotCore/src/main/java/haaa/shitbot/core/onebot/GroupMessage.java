package haaa.shitbot.core.onebot;

public final class GroupMessage {
    private final long groupId;
    private final long userId;
    private final long selfId;
    private final String rawMessage;

    public GroupMessage(long groupId, long userId, long selfId, String rawMessage) {
        this.groupId = groupId;
        this.userId = userId;
        this.selfId = selfId;
        this.rawMessage = rawMessage == null ? "" : rawMessage;
    }

    public long getGroupId() { return groupId; }
    public long getUserId() { return userId; }
    public long getSelfId() { return selfId; }
    public String getRawMessage() { return rawMessage; }
}
