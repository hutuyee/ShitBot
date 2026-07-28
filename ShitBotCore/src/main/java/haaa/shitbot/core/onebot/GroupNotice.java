package haaa.shitbot.core.onebot;

/** OneBot v11 group membership notice. */
public final class GroupNotice {
    public enum Type {
        INCREASE,
        DECREASE
    }

    private final Type type;
    private final long groupId;
    private final long userId;
    private final long selfId;
    private final long operatorId;
    private final String subType;

    public GroupNotice(Type type,
                       long groupId,
                       long userId,
                       long selfId,
                       long operatorId,
                       String subType) {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        this.type = type;
        this.groupId = groupId;
        this.userId = userId;
        this.selfId = selfId;
        this.operatorId = operatorId;
        this.subType = subType == null ? "" : subType;
    }

    public Type getType() {
        return type;
    }

    public long getGroupId() {
        return groupId;
    }

    public long getUserId() {
        return userId;
    }

    public long getSelfId() {
        return selfId;
    }

    public long getOperatorId() {
        return operatorId;
    }

    public String getSubType() {
        return subType;
    }
}
