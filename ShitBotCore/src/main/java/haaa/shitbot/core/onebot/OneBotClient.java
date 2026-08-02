package haaa.shitbot.core.onebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * OneBot v11 forward WebSocket client.
 * Authentication is sent as the standard Authorization: Bearer &lt;token&gt; header.
 */
public final class OneBotClient implements AutoCloseable {
    private static final int MAX_INBOUND_MESSAGE_LENGTH = 2 * 1024 * 1024;

    private final Settings.OneBot settings;
    private final PlatformBridge platform;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("shitbot-onebot", true));
    private final ConcurrentHashMap<String, PendingAction> pendingActions = new ConcurrentHashMap<String, PendingAction>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectDelaySeconds;
    private final AtomicLong lastReceivedAt = new AtomicLong();
    private volatile Consumer<GroupMessage> groupMessageConsumer;
    private volatile Consumer<GroupNotice> groupNoticeConsumer;
    private volatile Client client;

    public OneBotClient(Settings.OneBot settings, PlatformBridge platform) {
        this.settings = settings;
        this.platform = platform;
        this.reconnectDelaySeconds = new AtomicInteger(settings.getReconnectInitialSeconds());
    }

    public void setGroupMessageConsumer(Consumer<GroupMessage> consumer) {
        this.groupMessageConsumer = consumer;
    }

    public void setGroupNoticeConsumer(Consumer<GroupNotice> consumer) {
        this.groupNoticeConsumer = consumer;
    }

    public void start() {
        if (!settings.isEnabled() || closed.get()) {
            return;
        }
        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                connectNow();
            }
        });
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                checkHeartbeat();
                expirePendingActions();
            }
        }, 10L, 10L, TimeUnit.SECONDS);
    }

    private void connectNow() {
        if (closed.get() || !settings.isEnabled()) {
            return;
        }
        reconnectScheduled.set(false);
        Client previous = client;
        if (previous != null && previous.getReadyState() != ReadyState.CLOSED) {
            return;
        }
        try {
            URI uri = URI.create(settings.getWebsocketUrl());
            Map<String, String> headers = new HashMap<String, String>();
            if (!settings.getAccessToken().isEmpty()) {
                headers.put("Authorization", "Bearer " + settings.getAccessToken());
            }
            headers.put("User-Agent", "ShitBot-OneBot11/1.0");
            Client created = new Client(uri, headers, settings.getConnectTimeoutSeconds() * 1000);
            client = created;
            boolean connected = created.connectBlocking(settings.getConnectTimeoutSeconds(), TimeUnit.SECONDS);
            if (!connected) {
                platform.warn("OneBot WebSocket connection timed out: " + settings.getWebsocketUrl());
                scheduleReconnect();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            platform.warn("OneBot WebSocket connection failed: " + FutureUtil.unwrap(throwable).getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (closed.get() || !settings.isEnabled() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int delay = reconnectDelaySeconds.get();
        int next = Math.min(settings.getReconnectMaximumSeconds(), Math.max(delay + 1, delay * 2));
        reconnectDelaySeconds.set(next);
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                connectNow();
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void checkHeartbeat() {
        if (closed.get()) {
            return;
        }
        Client current = client;
        if (current == null || !current.isOpen()) {
            scheduleReconnect();
            return;
        }
        long last = lastReceivedAt.get();
        if (last > 0L && System.currentTimeMillis() - last > settings.getHeartbeatTimeoutSeconds() * 1000L) {
            platform.warn("OneBot heartbeat timeout, reconnecting.");
            try {
                current.close(1000, "heartbeat timeout");
            } catch (Throwable ignored) {
            }
            scheduleReconnect();
        }
    }

    public CompletableFuture<JsonNode> callAction(String action, ObjectNode parameters) {
        if (action == null || action.trim().isEmpty()) {
            return FutureUtil.failedFuture(new IllegalArgumentException("action cannot be empty"));
        }
        Client current = client;
        if (closed.get() || current == null || !current.isOpen()) {
            return FutureUtil.failedFuture(new IllegalStateException("OneBot WebSocket is not connected"));
        }

        String echo = UUID.randomUUID().toString();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("action", action);
        request.set("params", parameters == null ? objectMapper.createObjectNode() : parameters);
        request.put("echo", echo);

        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>();
        PendingAction pending = new PendingAction(future,
                System.currentTimeMillis() + settings.getActionTimeoutSeconds() * 1000L);
        pendingActions.put(echo, pending);
        try {
            current.send(objectMapper.writeValueAsString(request));
        } catch (Throwable throwable) {
            pendingActions.remove(echo);
            future.completeExceptionally(throwable);
        }
        return future;
    }

    public CompletableFuture<JsonNode> sendGroupText(long groupId, String text, Long atUserId) {
        ArrayNode message = objectMapper.createArrayNode();
        if (atUserId != null && atUserId.longValue() > 0L) {
            ObjectNode at = objectMapper.createObjectNode();
            at.put("type", "at");
            ObjectNode data = objectMapper.createObjectNode();
            data.put("qq", String.valueOf(atUserId.longValue()));
            at.set("data", data);
            message.add(at);
        }
        ObjectNode textSegment = objectMapper.createObjectNode();
        textSegment.put("type", "text");
        ObjectNode textData = objectMapper.createObjectNode();
        textData.put("text", text == null ? "" : text);
        textSegment.set("data", textData);
        message.add(textSegment);

        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("group_id", groupId);
        parameters.set("message", message);
        parameters.put("auto_escape", false);
        return callAction("send_group_msg", parameters);
    }

    public CompletableFuture<JsonNode> sendGroupImage(long groupId, byte[] pngBytes, String fileName) {
        if (pngBytes == null || pngBytes.length == 0) {
            return FutureUtil.failedFuture(new IllegalArgumentException("Image is empty"));
        }
        String encoded = Base64.getEncoder().encodeToString(pngBytes);
        ArrayNode message = objectMapper.createArrayNode();
        ObjectNode imageSegment = objectMapper.createObjectNode();
        imageSegment.put("type", "image");
        ObjectNode imageData = objectMapper.createObjectNode();
        imageData.put("file", "base64://" + encoded);
        if (fileName != null && !fileName.trim().isEmpty()) {
            imageData.put("name", fileName.trim());
        }
        imageSegment.set("data", imageData);
        message.add(imageSegment);

        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("group_id", groupId);
        parameters.set("message", message);
        parameters.put("auto_escape", false);
        return callAction("send_group_msg", parameters);
    }

    public boolean isConnected() {
        Client current = client;
        return current != null && current.isOpen() && !closed.get();
    }

    private void handleMessage(String message) {
        lastReceivedAt.set(System.currentTimeMillis());
        if (message == null || message.isEmpty() || message.length() > MAX_INBOUND_MESSAGE_LENGTH) {
            if (message != null && message.length() > MAX_INBOUND_MESSAGE_LENGTH) {
                platform.warn("Ignored oversized OneBot WebSocket message: " + message.length() + " chars");
            }
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode echoNode = root.get("echo");
            if (echoNode != null && !echoNode.isNull()) {
                handleActionResponse(root, echoNode.asText());
                return;
            }
            handleEvent(root);
        } catch (Throwable throwable) {
            platform.warn("Failed to parse OneBot message: " + FutureUtil.unwrap(throwable).getMessage());
        }
    }

    private void handleActionResponse(JsonNode root, String echo) {
        PendingAction pending = pendingActions.remove(echo);
        if (pending == null) {
            return;
        }
        String status = root.path("status").asText("");
        int retCode = root.path("retcode").asInt(0);
        if ("ok".equalsIgnoreCase(status) && retCode == 0) {
            JsonNode data = root.get("data");
            pending.future.complete(data == null ? objectMapper.nullNode() : data);
        } else {
            String message = root.path("message").asText(root.path("wording").asText("OneBot action failed"));
            pending.future.completeExceptionally(new IllegalStateException(
                    message + " (retcode=" + retCode + ")"));
        }
    }

    private void handleEvent(JsonNode root) {
        String postType = root.path("post_type").asText("");
        if ("message".equalsIgnoreCase(postType)) {
            handleGroupMessageEvent(root);
            return;
        }
        if ("notice".equalsIgnoreCase(postType)) {
            handleGroupNoticeEvent(root);
        }
    }

    private void handleGroupMessageEvent(JsonNode root) {
        if (!"group".equalsIgnoreCase(root.path("message_type").asText(""))) {
            return;
        }
        long groupId = root.path("group_id").asLong(0L);
        long userId = root.path("user_id").asLong(0L);
        long selfId = root.path("self_id").asLong(0L);
        if (!isAcceptedGroupUser(groupId, userId, selfId)) {
            return;
        }
        String rawMessage = extractText(root, selfId);
        if (rawMessage.trim().isEmpty()) {
            return;
        }
        String senderName = extractSenderName(root, userId);
        Consumer<GroupMessage> consumer = groupMessageConsumer;
        if (consumer != null) {
            try {
                consumer.accept(new GroupMessage(groupId, userId, selfId, rawMessage, senderName));
            } catch (Throwable throwable) {
                platform.error("Unhandled OneBot group message error", throwable);
            }
        }
    }

    private void handleGroupNoticeEvent(JsonNode root) {
        String noticeType = root.path("notice_type").asText("");
        GroupNotice.Type type;
        if ("group_increase".equalsIgnoreCase(noticeType)) {
            type = GroupNotice.Type.INCREASE;
        } else if ("group_decrease".equalsIgnoreCase(noticeType)) {
            type = GroupNotice.Type.DECREASE;
        } else {
            return;
        }

        long groupId = root.path("group_id").asLong(0L);
        long userId = root.path("user_id").asLong(0L);
        long selfId = root.path("self_id").asLong(0L);
        if (!isAcceptedGroupUser(groupId, userId, selfId)) {
            return;
        }

        Consumer<GroupNotice> consumer = groupNoticeConsumer;
        if (consumer != null) {
            try {
                consumer.accept(new GroupNotice(
                        type,
                        groupId,
                        userId,
                        selfId,
                        root.path("operator_id").asLong(0L),
                        root.path("sub_type").asText("")));
            } catch (Throwable throwable) {
                platform.error("Unhandled OneBot group notice error", throwable);
            }
        }
    }

    private boolean isAcceptedGroupUser(long groupId, long userId, long selfId) {
        return groupId > 0L
                && userId > 0L
                && userId != selfId
                && settings.isGroupAllowed(groupId);
    }

    private String extractSenderName(JsonNode root, long userId) {
        JsonNode sender = root.get("sender");
        if (sender != null && sender.isObject()) {
            String card = sender.path("card").asText("").trim();
            if (!card.isEmpty()) {
                return card;
            }
            String nickname = sender.path("nickname").asText("").trim();
            if (!nickname.isEmpty()) {
                return nickname;
            }
        }
        return String.valueOf(userId);
    }

    private String extractText(JsonNode root, long selfId) {
        // Prefer message segments: they remove leading CQ at-codes when users mention the bot.
        JsonNode message = root.get("message");
        if (message != null && message.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode segment : message) {
                if ("text".equals(segment.path("type").asText())) {
                    builder.append(segment.path("data").path("text").asText(""));
                }
            }
            if (builder.length() > 0) {
                return builder.toString();
            }
        }
        if (message != null && message.isTextual()) {
            return stripLeadingSelfMention(message.asText(), selfId);
        }
        JsonNode raw = root.get("raw_message");
        return raw != null && raw.isTextual() ? stripLeadingSelfMention(raw.asText(), selfId) : "";
    }

    private String stripLeadingSelfMention(String text, long selfId) {
        String result = text == null ? "" : text.trim();
        if (selfId <= 0L) {
            return result;
        }
        String prefix = "[CQ:at,qq=" + selfId;
        while (result.startsWith(prefix)) {
            int segmentEnd = result.indexOf(']');
            if (segmentEnd < 0) {
                break;
            }
            result = result.substring(segmentEnd + 1).trim();
        }
        return result;
    }

    private void expirePendingActions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PendingAction> entry : pendingActions.entrySet()) {
            PendingAction pending = entry.getValue();
            if (pending.deadlineAt <= now && pendingActions.remove(entry.getKey(), pending)) {
                pending.future.completeExceptionally(new TimeoutException("OneBot action timed out"));
            }
        }
    }

    private void failAllPending(Throwable throwable) {
        for (Map.Entry<String, PendingAction> entry : pendingActions.entrySet()) {
            PendingAction pending = entry.getValue();
            if (pendingActions.remove(entry.getKey(), pending)) {
                pending.future.completeExceptionally(throwable);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        reconnectScheduled.set(false);
        Client current = client;
        client = null;
        if (current != null) {
            try {
                current.closeBlocking();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
            }
        }
        failAllPending(new IllegalStateException("OneBot client closed"));
        scheduler.shutdownNow();
    }

    private final class Client extends WebSocketClient {
        private Client(URI serverUri, Map<String, String> headers, int connectTimeoutMs) {
            super(serverUri, new Draft_6455(), headers == null ? Collections.<String, String>emptyMap() : headers,
                    connectTimeoutMs);
            setConnectionLostTimeout(Math.max(15, settings.getHeartbeatTimeoutSeconds() / 2));
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            reconnectDelaySeconds.set(settings.getReconnectInitialSeconds());
            reconnectScheduled.set(false);
            lastReceivedAt.set(System.currentTimeMillis());
            platform.info("OneBot WebSocket connected: " + getURI());
        }

        @Override
        public void onMessage(String message) {
            handleMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            if (!closed.get()) {
                platform.warn("OneBot WebSocket closed (code=" + code + ", remote=" + remote + "): "
                        + (reason == null ? "" : reason));
                failAllPending(new IllegalStateException("OneBot WebSocket disconnected"));
                scheduleReconnect();
            }
        }

        @Override
        public void onError(Exception exception) {
            if (!closed.get()) {
                platform.warn("OneBot WebSocket error: "
                        + (exception == null ? "unknown" : exception.getMessage()));
            }
        }
    }

    private static final class PendingAction {
        private final CompletableFuture<JsonNode> future;
        private final long deadlineAt;

        private PendingAction(CompletableFuture<JsonNode> future, long deadlineAt) {
            this.future = future;
            this.deadlineAt = deadlineAt;
        }
    }
}
