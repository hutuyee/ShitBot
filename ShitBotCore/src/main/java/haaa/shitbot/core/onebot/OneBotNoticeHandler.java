package haaa.shitbot.core.onebot;

import com.fasterxml.jackson.databind.JsonNode;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.service.BindingService;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.util.concurrent.CompletableFuture;

/** Handles OneBot group join and leave notices. */
public final class OneBotNoticeHandler {
    private final Settings settings;
    private final PlatformBridge platform;
    private final BindingService bindingService;
    private final OneBotClient client;

    public OneBotNoticeHandler(Settings settings,
                               PlatformBridge platform,
                               BindingService bindingService,
                               OneBotClient client) {
        this.settings = settings;
        this.platform = platform;
        this.bindingService = bindingService;
        this.client = client;
    }

    public void handle(GroupNotice notice) {
        if (notice == null) {
            return;
        }
        if (notice.getType() == GroupNotice.Type.INCREASE) {
            handleGroupIncrease(notice);
            return;
        }
        if (notice.getType() == GroupNotice.Type.DECREASE) {
            handleGroupDecrease(notice);
        }
    }

    private void handleGroupIncrease(final GroupNotice notice) {
        Settings.GroupJoinWelcome welcome = settings.getOneBot().getGroupJoinWelcome();
        if (!welcome.isEnabled()) {
            return;
        }

        String text = welcome.getMessage();
        boolean mentionNewMember = text.contains("%at%") || text.contains("%艾特%");
        text = text.replace("%at%", "").replace("%艾特%", "");
        text = TextUtil.replace(text, "%qq%", Long.valueOf(notice.getUserId()));
        text = TextUtil.replace(text, "%group%", Long.valueOf(notice.getGroupId()));
        text = TextUtil.replace(text, "%sub_type%", notice.getSubType());

        Long atUserId = mentionNewMember ? Long.valueOf(notice.getUserId()) : null;
        client.sendGroupText(notice.getGroupId(), text, atUserId).exceptionally(
                new java.util.function.Function<Throwable, JsonNode>() {
                    @Override
                    public JsonNode apply(Throwable throwable) {
                        platform.warn("Failed to send group join welcome message: "
                                + FutureUtil.unwrap(throwable).getMessage());
                        return null;
                    }
                });
    }

    private void handleGroupDecrease(final GroupNotice notice) {
        if (!settings.getOneBot().getGroupLeaveUnbind().isEnabled()) {
            return;
        }

        final String qqId = String.valueOf(notice.getUserId());
        CompletableFuture<Integer> future = bindingService.unbindByQqId(qqId);
        future.whenComplete(new java.util.function.BiConsumer<Integer, Throwable>() {
            @Override
            public void accept(Integer removedRows, Throwable throwable) {
                if (throwable != null) {
                    platform.error("Failed to remove QQ binding after group leave: qq=" + qqId,
                            FutureUtil.unwrap(throwable));
                    return;
                }
                if (removedRows != null && removedRows.intValue() > 0) {
                    platform.info("Removed QQ binding after group leave: qq=" + qqId
                            + ", group=" + notice.getGroupId()
                            + ", sub_type=" + notice.getSubType());
                }
            }
        });
    }
}
