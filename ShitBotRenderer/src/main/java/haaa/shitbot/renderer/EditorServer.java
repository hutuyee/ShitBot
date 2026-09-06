package haaa.shitbot.renderer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import haaa.shitbot.api.ImageRenderRequest;
import haaa.shitbot.api.ImageRenderResult;
import haaa.shitbot.api.ImageTemplateInfo;
import haaa.shitbot.api.spi.ImageTemplateEngineHost;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class EditorServer implements AutoCloseable {
    private static final String COOKIE_NAME = "ShitBotEditor";
    private static final long SESSION_MILLIS = TimeUnit.MINUTES.toMillis(30L);
    private static final int MAX_LOGIN_TOKENS = 32;
    private static final int MAX_SOURCE_BODY = 2 * 1024 * 1024;

    private final ImageTemplateEngineSettings settings;
    private final ImageTemplateEngineHost host;
    private final TemplateRepository repository;
    private final RendererEngine engine;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Long> loginTokens = new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<String, Long>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3,
            new RendererThreadFactory("editor"));
    private final YamlDocuments yaml = new YamlDocuments();
    private volatile HttpServer server;
    private volatile InetAddress boundAddress;

    EditorServer(ImageTemplateEngineSettings settings,
                 ImageTemplateEngineHost host,
                 TemplateRepository repository,
                 RendererEngine engine) {
        this.settings = settings;
        this.host = host;
        this.repository = repository;
        this.engine = engine;
    }

    void start() throws IOException {
        InetAddress address = InetAddress.getByName(settings.getEditorBindAddress());
        if (!address.isLoopbackAddress()) {
            throw new IOException("Image template editor may only listen on a loopback address");
        }
        HttpServer created = HttpServer.create(
                new InetSocketAddress(address, settings.getEditorPort()), 16);
        created.createContext("/login", new LoginHandler());
        created.createContext("/api/", new ApiHandler());
        created.createContext("/", new StaticHandler());
        created.setExecutor(executor);
        created.start();
        server = created;
        boundAddress = address;
        host.info("Image template editor listening on " + address.getHostAddress()
                + ':' + created.getAddress().getPort() + ".");
    }

    String createLoginUrl() {
        HttpServer current = server;
        if (current == null) throw new IllegalStateException("Image template editor is not running");
        cleanupAuth();
        while (loginTokens.size() >= MAX_LOGIN_TOKENS) {
            loginTokens.remove(loginTokens.keySet().iterator().next());
        }
        String token = token();
        loginTokens.put(token, Long.valueOf(System.currentTimeMillis()
                + settings.getEditorLoginSeconds() * 1000L));
        String hostValue = boundAddress.getHostAddress();
        if (hostValue.contains(":")) hostValue = '[' + hostValue + ']';
        return "http://" + hostValue + ':' + current.getAddress().getPort() + "/login?token=" + token;
    }

    private final class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            String supplied = query(exchange.getRequestURI()).get("token");
            Long expires = supplied == null ? null : loginTokens.remove(supplied);
            if (expires == null || expires.longValue() < System.currentTimeMillis()) {
                sendError(exchange, 403, "This editor login link is invalid, expired, or already used.");
                return;
            }
            String session = token();
            sessions.put(session, Long.valueOf(System.currentTimeMillis() + SESSION_MILLIS));
            secureHeaders(exchange.getResponseHeaders());
            exchange.getResponseHeaders().add("Set-Cookie", COOKIE_NAME + '=' + session
                    + "; Path=/; HttpOnly; SameSite=Strict");
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(303, -1L);
            exchange.close();
        }
    }

    private final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            if (!authorized(exchange)) {
                sendError(exchange, 401, "Open a fresh editor link from /shitbot editor.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) sendResource(exchange, "/editor/index.html", "text/html; charset=utf-8");
            else if ("/editor.css".equals(path)) sendResource(exchange, "/editor/editor.css", "text/css; charset=utf-8");
            else if ("/editor.js".equals(path)) sendResource(exchange, "/editor/editor.js", "application/javascript; charset=utf-8");
            else sendError(exchange, 404, "Not found");
        }
    }

    private final class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!authorized(exchange)) {
                sendError(exchange, 401, "Editor session expired");
                return;
            }
            if (!sameOrigin(exchange)) {
                sendError(exchange, 403, "Invalid editor request origin");
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method)
                    && !"1".equals(exchange.getRequestHeaders().getFirst("X-ShitBot-Editor"))) {
                sendError(exchange, 403, "Missing editor request token");
                return;
            }
            try {
                route(exchange, method, exchange.getRequestURI().getPath().substring("/api/".length()),
                        query(exchange.getRequestURI()));
            } catch (Throwable throwable) {
                Throwable cause = rootCause(throwable);
                host.warn("Image editor request failed: " + safeMessage(cause));
                sendError(exchange, cause instanceof IllegalArgumentException ? 400 : 422, safeMessage(cause));
            }
        }
    }

    private void route(HttpExchange exchange,
                       String method,
                       String route,
                       Map<String, String> query) throws Exception {
        if ("GET".equals(method) && "templates".equals(route)) {
            List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
            Map<String, ImageTemplateInfo> published = new LinkedHashMap<String, ImageTemplateInfo>();
            for (ImageTemplateInfo info : repository.getPublishedTemplates()) published.put(info.getId(), info);
            for (String id : repository.listTemplateIds()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", id);
                ImageTemplateInfo info = published.get(id);
                item.put("published", Boolean.valueOf(info != null));
                if (info != null) {
                    item.put("name", info.getName());
                    item.put("version", Long.valueOf(info.getVersion()));
                    item.put("width", Integer.valueOf(info.getWidth()));
                    item.put("height", Integer.valueOf(info.getHeight()));
                }
                values.add(item);
            }
            sendJson(exchange, 200, values);
            return;
        }
        String id = required(query, "id");
        if ("GET".equals(method) && "template".equals(route)) {
            sendJson(exchange, 200, repository.readDraftBundle(id));
        } else if ("POST".equals(method) && "template".equals(route)) {
            Map<String, Object> body = bodyMap(exchange, 64 * 1024);
            repository.createDraft(id, string(body.get("name")));
            sendJson(exchange, 201, status("created", id));
        } else if ("GET".equals(method) && "source".equals(route)) {
            sendJson(exchange, 200, repository.readDraftSources(id));
        } else if ("PUT".equals(method) && "draft".equals(route)) {
            Map<String, Object> body = bodyMap(exchange, MAX_SOURCE_BODY);
            repository.saveDraft(id, map(body.get("manifest")), map(body.get("scene")));
            sendJson(exchange, 200, status("saved", id));
        } else if ("PUT".equals(method) && "source".equals(route)) {
            Map<String, Object> body = bodyMap(exchange, MAX_SOURCE_BODY);
            repository.saveDraftSources(id, string(body.get("manifest")), string(body.get("scene")));
            sendJson(exchange, 200, status("saved", id));
        } else if ("PUT".equals(method) && "asset".equals(route)) {
            String name = required(query, "name");
            repository.uploadAsset(id, name,
                    readBody(exchange, settings.getEditorMaximumUploadBytes()));
            sendJson(exchange, 200, status("uploaded", name));
        } else if ("POST".equals(method) && "preview".equals(route)) {
            ImageRenderRequest request = renderRequest(bodyMap(exchange, MAX_SOURCE_BODY));
            ImageRenderResult result = engine.renderDraft(id, request).get(
                    settings.getRenderTimeoutMillis() + 10000L, TimeUnit.MILLISECONDS);
            sendBytes(exchange, 200, result.getBytes(), "image/png");
        } else if ("POST".equals(method) && "resolve".equals(route)) {
            ImageRenderRequest request = renderRequest(bodyMap(exchange, MAX_SOURCE_BODY));
            TemplateSnapshot draft = repository.loadDraft(id);
            Map<String, Object> data = host.resolveData(id, draft.getInfo().getProviders(), request)
                    .get(settings.getRenderTimeoutMillis() + 10000L, TimeUnit.MILLISECONDS);
            sendJson(exchange, 200, data);
        } else if ("POST".equals(method) && "publish".equals(route)) {
            long version = repository.publish(id);
            Map<String, Object> response = status("published", id);
            response.put("version", Long.valueOf(version));
            sendJson(exchange, 200, response);
        } else if ("POST".equals(method) && "rollback".equals(route)) {
            long version = Long.parseLong(required(query, "version"));
            repository.rollback(id, version);
            Map<String, Object> response = status("rolled-back", id);
            response.put("version", Long.valueOf(version));
            sendJson(exchange, 200, response);
        } else {
            sendError(exchange, 404, "Unknown editor endpoint");
        }
    }

    private ImageRenderRequest renderRequest(Map<String, Object> body) throws IOException {
        Object context = body.get("context");
        Object data = body.get("data");
        return new ImageRenderRequest(context == null
                ? Collections.<String, Object>emptyMap() : map(context),
                data == null ? Collections.<String, Object>emptyMap() : map(data));
    }

    private Map<String, Object> bodyMap(HttpExchange exchange, long maximum) throws IOException {
        byte[] bytes = readBody(exchange, maximum);
        if (bytes.length == 0) return new LinkedHashMap<String, Object>();
        return yaml.loadMap(new String(bytes, StandardCharsets.UTF_8));
    }

    private byte[] readBody(HttpExchange exchange, long maximum) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared) > maximum) throw new IOException("Request body is too large");
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid Content-Length", exception);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maximum, 8192L));
        try (InputStream input = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maximum) throw new IOException("Request body is too large");
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private boolean authorized(HttpExchange exchange) {
        cleanupAuth();
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return false;
        try {
            for (HttpCookie value : HttpCookie.parse(cookie)) {
                if (!COOKIE_NAME.equals(value.getName())) continue;
                Long expires = sessions.get(value.getValue());
                if (expires == null || expires.longValue() < System.currentTimeMillis()) return false;
                sessions.put(value.getValue(), Long.valueOf(System.currentTimeMillis() + SESSION_MILLIS));
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return false;
    }

    private boolean sameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.trim().isEmpty()) return true;
        try {
            URL url = new URL(origin);
            if (!("http".equalsIgnoreCase(url.getProtocol())
                    || "https".equalsIgnoreCase(url.getProtocol()))
                    || url.getUserInfo() != null || !url.getPath().isEmpty()) return false;
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host != null && url.getAuthority().equalsIgnoreCase(host.trim())) {
                return true;
            }
            InetAddress address = InetAddress.getByName(url.getHost());
            HttpServer current = server;
            return address.isLoopbackAddress() && current != null
                    && url.getPort() == current.getAddress().getPort();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void cleanupAuth() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : loginTokens.entrySet()) {
            if (entry.getValue().longValue() < now) loginTokens.remove(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Long> entry : sessions.entrySet()) {
            if (entry.getValue().longValue() < now) sessions.remove(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, String> query(URI uri) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return result;
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            result.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
        }
        return result;
    }

    private String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value.trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) throws IOException {
        if (!(value instanceof Map<?, ?>)) throw new IOException("Expected a JSON/YAML object");
        return (Map<String, Object>) value;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> status(String status, String value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("value", value);
        return result;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void sendResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        byte[] bytes;
        try (InputStream input = EditorServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendError(exchange, 404, "Editor resource is unavailable");
                return;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
                if (output.size() > 512 * 1024) throw new IOException("Editor resource is too large");
            }
            bytes = output.toByteArray();
        }
        sendBytes(exchange, 200, bytes, contentType);
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendBytes(exchange, status, JsonCodec.encode(value).getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8");
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("error", message == null ? "Request failed" : message);
        sendJson(exchange, status, error);
    }

    private void sendBytes(HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        secureHeaders(headers);
        headers.set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private void secureHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? (throwable == null ? "Request failed" : throwable.getClass().getSimpleName()) : message;
    }

    @Override
    public void close() {
        HttpServer current = server;
        server = null;
        if (current != null) current.stop(0);
        executor.shutdownNow();
        loginTokens.clear();
        sessions.clear();
    }
}
