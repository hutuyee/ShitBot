package haaa.shitbot.core.onebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;
import haaa.shitbot.core.util.NetworkUtil;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
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
    private static final Logger log = LoggerFactory.getLogger(OneBotClient.class);

    private final Settings.OneBot settings;
    private final Settings.MediaMode mediaMode;
    private final PlatformBridge platform;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("shitbot-onebot", true));
    private final ConcurrentHashMap<String, PendingAction> pendingActions = new ConcurrentHashMap<String, PendingAction>();
    private final Semaphore pendingCapacity;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectDelaySeconds;
    private final AtomicLong lastReceivedAt = new AtomicLong();
    private volatile Consumer<GroupMessage> groupMessageConsumer;
    private volatile Consumer<GroupNotice> groupNoticeConsumer;
    private volatile Client client;

    public OneBotClient(Settings.OneBot settings, PlatformBridge platform) {
        this(settings, Settings.MediaMode.BROWSER, platform);
    }

    public OneBotClient(Settings.OneBot settings, Settings.MediaMode mediaMode, PlatformBridge platform) {
        this.settings = settings;
        this.mediaMode = mediaMode == null ? Settings.MediaMode.BROWSER : mediaMode;
        this.platform = platform;
        this.pendingCapacity = new Semaphore(settings.getMaximumPendingActions());
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
        warnIfInsecureRemoteWebSocket();
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
            }
        }, 10L, 10L, TimeUnit.SECONDS);
    }

    private void warnIfInsecureRemoteWebSocket() {
        try {
            URI uri = URI.create(settings.getWebsocketUrl());
            if (!"ws".equalsIgnoreCase(uri.getScheme()) || NetworkUtil.isLoopbackHost(uri.getHost())) {
                return;
            }
            if (settings.getAccessToken().isEmpty()) {
                platform.warn("Remote OneBot uses unencrypted ws:// without an access token: " + uri);
            } else {
                platform.warn("Remote OneBot uses unencrypted ws://; the Bearer token and messages are exposed in transit: "
                        + uri);
            }
        } catch (IllegalArgumentException ignored) {
            // connectNow reports malformed URLs through the normal connection error path.
        }
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
        if (!pendingCapacity.tryAcquire()) {
            return FutureUtil.failedFuture(new IllegalStateException(
                    "OneBot pending action limit reached: " + settings.getMaximumPendingActions()));
        }

        final String echo = UUID.randomUUID().toString();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("action", action);
        request.set("params", parameters == null ? objectMapper.createObjectNode() : parameters);
        request.put("echo", echo);

        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>();
        final PendingAction pending = new PendingAction(future);
        pendingActions.put(echo, pending);
        try {
            pending.setTimeoutTask(scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    if (pendingActions.remove(echo, pending)) {
                        pending.releaseCapacity(pendingCapacity);
                        pending.future.completeExceptionally(new TimeoutException("OneBot action timed out"));
                    }
                }
            }, settings.getActionTimeoutSeconds(), TimeUnit.SECONDS));
            current.send(objectMapper.writeValueAsString(request));
        } catch (Throwable throwable) {
            if (pendingActions.remove(echo, pending)) {
                pending.cancelTimeout();
                pending.releaseCapacity(pendingCapacity);
                future.completeExceptionally(throwable);
            }
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
        pending.cancelTimeout();
        pending.releaseCapacity(pendingCapacity);
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
        ParsedMessage parsedMessage = extractMessage(root, selfId);
        if (parsedMessage.commandText.trim().isEmpty() && parsedMessage.parts.isEmpty()) {
            return;
        }
        String senderName = extractSenderName(root, userId);
        Consumer<GroupMessage> consumer = groupMessageConsumer;
        if (consumer != null) {
            try {
                consumer.accept(new GroupMessage(
                        groupId, userId, selfId, parsedMessage.commandText, senderName, parsedMessage.parts));
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

    private ParsedMessage extractMessage(JsonNode root, long selfId) {
        JsonNode message = root.get("message");
        if (message != null && message.isArray()) {
            return extractArrayMessage(message, selfId);
        }
        String encoded = "";
        if (message != null && message.isTextual()) {
            encoded = message.asText("");
        } else {
            JsonNode raw = root.get("raw_message");
            if (raw != null && raw.isTextual()) {
                encoded = raw.asText("");
            }
        }
        return extractCqMessage(encoded, selfId);
    }

    private ParsedMessage extractArrayMessage(JsonNode message, long selfId) {
        StringBuilder commandText = new StringBuilder();
        List<ChatPart> parts = new ArrayList<ChatPart>();
        for (JsonNode segment : message) {
            String type = segment.path("type").asText("");
            JsonNode data = segment.path("data");
            appendSegment(parts, commandText, type, data, selfId);
        }
        return new ParsedMessage(commandText.toString(), trimParts(parts));
    }

    private ParsedMessage extractCqMessage(String encoded, long selfId) {
        String text = encoded == null ? "" : encoded;
        StringBuilder commandText = new StringBuilder();
        List<ChatPart> parts = new ArrayList<ChatPart>();
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf("[CQ:", cursor);
            if (start < 0) {
                appendText(parts, commandText, decodeCq(text.substring(cursor)));
                break;
            }
            if (start > cursor) {
                appendText(parts, commandText, decodeCq(text.substring(cursor, start)));
            }
            int close = text.indexOf(']', start + 4);
            if (close < 0) {
                appendText(parts, commandText, decodeCq(text.substring(start)));
                break;
            }
            String body = text.substring(start + 4, close);
            String[] values = body.split(",");
            String type = values.length == 0 ? "" : values[0];
            ObjectNode data = objectMapper.createObjectNode();
            for (int i = 1; i < values.length; i++) {
                int equals = values[i].indexOf('=');
                if (equals > 0) {
                    data.put(values[i].substring(0, equals), decodeCq(values[i].substring(equals + 1)));
                }
            }
            appendSegment(parts, commandText, type, data, selfId);
            cursor = close + 1;
        }
        if (text.isEmpty()) {
            return new ParsedMessage("", Collections.<ChatPart>emptyList());
        }
        return new ParsedMessage(commandText.toString(), trimParts(parts));
    }

    private void appendSegment(List<ChatPart> parts,
                               StringBuilder commandText,
                               String rawType,
                               JsonNode data,
                               long selfId) {
        String type = rawType == null ? "" : rawType.toLowerCase(Locale.ROOT);
        if ("text".equals(type)) {
            appendText(parts, commandText, data.path("text").asText(""));
            return;
        }
        if ("at".equals(type)) {
            String qq = data.path("qq").asText("").trim();
            if (isLeadingWhitespaceOnly(parts) && qq.equals(String.valueOf(selfId))) {
                return;
            }
            String name = firstNonBlank(data, "name", "text");
            if ("all".equalsIgnoreCase(qq)) {
                name = "全体成员";
            }
            appendToken(parts, "@" + (name.isEmpty() ? qq : name), null, null);
            return;
        }
        if ("image".equals(type)) {
            String summary = cleanSummary(firstNonBlank(data, "summary", "text"));
            String label = mediaMode == Settings.MediaMode.BROWSER
                    ? "[图片]"
                    : (summary.isEmpty() ? "[图片]" : summary);
            appendToken(parts, label, firstUrl(data, "url", "file"), mediaHover("图片"));
            return;
        }
        if ("face".equals(type)) {
            String summary = cleanSummary(firstNonBlank(data, "summary", "text", "name"));
            String id = data.path("id").asText("").trim();
            String url = firstUrl(data, "url", "file");
            if ((url == null || url.isEmpty()) && id.matches("\\d{1,5}")) {
                url = "https://koishi.js.org/QFace/gif/s" + id + ".gif";
            }
            appendToken(parts, summary.isEmpty() ? "[表情" + (id.isEmpty() ? "" : ":" + id) + "]" : summary,
                    url, url == null || url.isEmpty() ? null : mediaHover("表情"));
            return;
        }
        if ("mface".equals(type)) {
            String summary = cleanSummary(firstNonBlank(data, "summary", "text", "name"));
            appendToken(parts, summary.isEmpty() ? "[动画表情]" : summary,
                    firstUrl(data, "url"), mediaHover("表情"));
            return;
        }
        if ("record".equals(type)) {
            appendToken(parts, "[语音]", firstUrl(data, "url", "file"), "点击打开 QQ 语音");
            return;
        }
        if ("video".equals(type)) {
            appendToken(parts, "[视频]", firstUrl(data, "url", "file"), "点击打开 QQ 视频");
            return;
        }
        if ("file".equals(type)) {
            String name = firstNonBlank(data, "name", "file");
            appendToken(parts, name.isEmpty() ? "[文件]" : "[文件:" + singleLine(name, 48) + "]",
                    firstUrl(data, "url"), "点击打开 QQ 文件");
            return;
        }
        if ("share".equals(type)) {
            String title = firstNonBlank(data, "title", "content");
            appendToken(parts, title.isEmpty() ? "[分享]" : "[分享:" + singleLine(title, 48) + "]",
                    firstUrl(data, "url"), "点击打开 QQ 分享");
            return;
        }
        if ("location".equals(type)) {
            String title = firstNonBlank(data, "title", "content");
            appendToken(parts, title.isEmpty() ? "[位置]" : "[位置:" + singleLine(title, 48) + "]",
                    null, null);
            return;
        }
        if ("music".equals(type)) {
            String title = firstNonBlank(data, "title", "content");
            appendToken(parts, title.isEmpty() ? "[音乐]" : "[音乐:" + singleLine(title, 48) + "]",
                    firstUrl(data, "url", "jumpUrl"), "点击打开 QQ 音乐");
            return;
        }
        if ("reply".equals(type)) {
            appendToken(parts, "[回复]", null, null);
            return;
        }
        if ("forward".equals(type) || "node".equals(type)) {
            appendToken(parts, "[合并转发]", null, null);
            return;
        }
        if ("json".equals(type) || "xml".equals(type)) {
            appendToken(parts, "[卡片消息]", null, null);
            return;
        }
        if ("dice".equals(type)) {
            appendToken(parts, "[骰子:" + data.path("result").asText(data.path("id").asText("?")) + "]", null, null);
            return;
        }
        if ("rps".equals(type)) {
            appendToken(parts, "[猜拳:" + data.path("result").asText(data.path("id").asText("?")) + "]", null, null);
            return;
        }
        if ("poke".equals(type) || "shake".equals(type)) {
            appendToken(parts, "[戳一戳]", null, null);
            return;
        }
        if (!type.isEmpty()) {
            appendToken(parts, "[" + singleLine(type, 24) + "]", null, null);
        }
    }

    private void appendText(List<ChatPart> parts, StringBuilder commandText, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        commandText.append(value);
        parts.add(ChatPart.text(value));
    }

    private void appendToken(List<ChatPart> parts, String text, String url, String hover) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (!parts.isEmpty()) {
            ChatPart previous = parts.get(parts.size() - 1);
            String previousText = previous.getText();
            if (!previousText.isEmpty() && !Character.isWhitespace(previousText.charAt(previousText.length() - 1))) {
                parts.add(ChatPart.text(" "));
            }
        }
        parts.add(url == null || url.trim().isEmpty()
                ? ChatPart.text(text.trim())
                : ChatPart.link(text.trim(), url, hover));
        parts.add(ChatPart.text(" "));
    }

    private boolean isLeadingWhitespaceOnly(List<ChatPart> parts) {
        for (ChatPart part : parts) {
            if (part != null && !part.getText().trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<ChatPart> trimParts(List<ChatPart> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder full = new StringBuilder();
        for (ChatPart part : source) {
            full.append(part == null ? "" : part.getText());
        }
        int start = 0;
        int end = full.length();
        while (start < end && Character.isWhitespace(full.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(full.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return Collections.emptyList();
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
        return Collections.unmodifiableList(result);
    }

    private String firstNonBlank(JsonNode data, String... keys) {
        if (data == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = data.path(key).asText("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String firstUrl(JsonNode data, String... keys) {
        if (data == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = data.path(key).asText("").trim();
            if (value.regionMatches(true, 0, "https://", 0, 8)
                    || value.regionMatches(true, 0, "http://", 0, 7)) {
                return value;
            }
        }
        return null;
    }

    private String cleanSummary(String value) {
        String clean = singleLine(value, 48);
        if (clean.equalsIgnoreCase("[CQ:image]") || clean.equalsIgnoreCase("[CQ:face]")) {
            return "";
        }
        return clean;
    }

    private String mediaHover(String kind) {
        if (mediaMode == Settings.MediaMode.PICTUREBRIDGE) {
            return "PictureBridge · QQ " + kind;
        }
        return "点击在浏览器查看" + kind;
    }

    private String singleLine(String value, int maximumLength) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= maximumLength ? clean : clean.substring(0, maximumLength - 1) + "…";
    }

    private String decodeCq(String value) {
        return value == null ? "" : value
                .replace("&#91;", "[")
                .replace("&#93;", "]")
                .replace("&#44;", ",")
                .replace("&amp;", "&");
    }

    private static final class ParsedMessage {
        private final String commandText;
        private final List<ChatPart> parts;

        private ParsedMessage(String commandText, List<ChatPart> parts) {
            this.commandText = commandText == null ? "" : commandText;
            this.parts = parts == null ? Collections.<ChatPart>emptyList() : parts;
        }
    }

    private void failAllPending(Throwable throwable) {
        for (Map.Entry<String, PendingAction> entry : pendingActions.entrySet()) {
            PendingAction pending = entry.getValue();
            if (pendingActions.remove(entry.getKey(), pending)) {
                pending.cancelTimeout();
                pending.releaseCapacity(pendingCapacity);
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
        private final AtomicBoolean capacityReleased = new AtomicBoolean();
        private ScheduledFuture<?> timeoutTask;

        private PendingAction(CompletableFuture<JsonNode> future) {
            this.future = future;
        }

        private synchronized void setTimeoutTask(ScheduledFuture<?> timeoutTask) {
            if (future.isDone()) {
                timeoutTask.cancel(false);
                return;
            }
            this.timeoutTask = timeoutTask;
        }

        private synchronized void cancelTimeout() {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
                timeoutTask = null;
            }
        }

        private void releaseCapacity(Semaphore capacity) {
            if (capacityReleased.compareAndSet(false, true)) {
                capacity.release();
            }
        }
    }
}
