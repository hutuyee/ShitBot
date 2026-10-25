package haaa.shitbot.core.image;

import haaa.shitbot.api.ImageDataProvider;
import haaa.shitbot.api.ImageDataProviderSpec;
import haaa.shitbot.api.ImageDataRequest;
import haaa.shitbot.api.ImageRenderRequest;
import haaa.shitbot.api.ImageRenderResult;
import haaa.shitbot.api.ImageTemplateInfo;
import haaa.shitbot.api.spi.ImageTemplateEngine;
import haaa.shitbot.api.spi.ImageTemplateEngineHost;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;
import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.service.PlayerProfileService;
import haaa.shitbot.core.service.InventoryService;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thin host for the optional renderer component and the public image API. */
public final class CustomImageService implements ImageTemplateEngineHost, AutoCloseable {
    private static final int MAX_PROVIDER_CACHE_ENTRIES = 256;

    private final Settings settings;
    private final Settings.CustomImages customSettings;
    private final PlatformBridge platform;
    private final PlayerProfileService profileService;
    private final InventoryService inventoryService;
    private final ConcurrentHashMap<String, ImageDataProvider> providers =
            new ConcurrentHashMap<String, ImageDataProvider>();
    private final Map<String, CachedProviderData> providerCache =
            new LinkedHashMap<String, CachedProviderData>(32, 0.75F, true);
    private final ExecutorService componentExecutor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("shitbot-image-component", true));
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("shitbot-image-data-timeout", true));
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile RendererComponentLoader.LoadedRenderer loadedRenderer;
    private volatile ImageTemplateEngine engine;
    private CompletableFuture<Void> startFuture;

    public CustomImageService(Settings settings, PlatformBridge platform) {
        this(settings, platform, null);
    }

    public CustomImageService(Settings settings,
                              PlatformBridge platform,
                              PlayerProfileService profileService) {
        this(settings, platform, profileService, null);
    }

    public CustomImageService(Settings settings,
                              PlatformBridge platform,
                              PlayerProfileService profileService,
                              InventoryService inventoryService) {
        this.settings = settings;
        this.customSettings = settings.getCustomImages();
        this.platform = platform;
        this.profileService = profileService;
        this.inventoryService = inventoryService;
        registerBuiltInProviders();
    }

    public synchronized CompletableFuture<Void> startAsync() {
        if (startFuture != null) {
            return startFuture;
        }
        if (!customSettings.isEnabled()) {
            startFuture = CompletableFuture.completedFuture(null);
            return startFuture;
        }
        if (closed.get()) {
            return FutureUtil.failedFuture(new IllegalStateException("Custom image service is closed"));
        }
        final CompletableFuture<RendererComponentLoader.LoadedRenderer> loading =
                CompletableFuture.supplyAsync(
                        new java.util.function.Supplier<RendererComponentLoader.LoadedRenderer>() {
                            @Override
                            public RendererComponentLoader.LoadedRenderer get() {
                                try {
                                    return new RendererComponentLoader(customSettings, settings.isDebug(), platform)
                                            .load(engineSettings(), CustomImageService.this);
                                } catch (IOException exception) {
                                    throw new java.util.concurrent.CompletionException(exception);
                                }
                            }
                        }, componentExecutor);
        startFuture = loading.thenCompose(
                new java.util.function.Function<RendererComponentLoader.LoadedRenderer,
                        CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> apply(RendererComponentLoader.LoadedRenderer loaded) {
                        if (closed.get()) {
                            loaded.close();
                            return FutureUtil.failedFuture(
                                    new IllegalStateException("Custom image service is closed"));
                        }
                        loadedRenderer = loaded;
                        engine = loaded.getEngine();
                        return engine.startAsync();
                    }
                });
        startFuture.whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
            @Override
            public void accept(Void ignored, Throwable throwable) {
                if (throwable != null) {
                    closeLoadedRenderer();
                } else if (customSettings.isEnabled()) {
                    platform.info("Custom image templates enabled with the external renderer component.");
                }
            }
        });
        return startFuture;
    }

    public boolean isCustomImageTemplatesEnabled() {
        return customSettings.isEnabled();
    }

    public CompletableFuture<ImageRenderResult> renderImage(String templateId,
                                                             ImageRenderRequest request) {
        ImageTemplateEngine current = engine;
        if (!customSettings.isEnabled()) {
            return FutureUtil.failedFuture(new IllegalStateException(
                    "Custom image templates are disabled in config.yml"));
        }
        if (closed.get() || current == null) {
            return FutureUtil.failedFuture(new IllegalStateException(
                    "Custom image renderer is not ready"));
        }
        return current.render(templateId, request == null
                ? ImageRenderRequest.of(Collections.<String, Object>emptyMap()) : request);
    }

    public List<ImageTemplateInfo> getImageTemplates() {
        ImageTemplateEngine current = engine;
        return current == null ? Collections.<ImageTemplateInfo>emptyList() : current.getTemplates();
    }

    public Optional<ImageTemplateInfo> getImageTemplate(String templateId) {
        ImageTemplateEngine current = engine;
        return current == null ? Optional.<ImageTemplateInfo>empty() : current.getTemplate(templateId);
    }

    public void registerImageDataProvider(ImageDataProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("image data provider cannot be null");
        }
        String id = normalizeProviderId(provider.getId());
        ImageDataProvider previous = providers.putIfAbsent(id, provider);
        if (previous != null && previous != provider) {
            throw new IllegalStateException("Image data provider is already registered: " + id);
        }
    }

    public boolean unregisterImageDataProvider(String providerId, ImageDataProvider provider) {
        String id = normalizeProviderId(providerId);
        if (isBuiltInProvider(id) || provider == null) {
            return false;
        }
        return providers.remove(id, provider);
    }

    public CompletableFuture<String> createImageEditorLoginUrl() {
        ImageTemplateEngine current = engine;
        if (!customSettings.isEnabled()) {
            return FutureUtil.failedFuture(new IllegalStateException(
                    "Custom image templates are disabled in config.yml"));
        }
        if (!customSettings.isEditorEnabled()) {
            return FutureUtil.failedFuture(new IllegalStateException(
                    "The custom image template editor is disabled in config.yml"));
        }
        if (current == null) {
            return FutureUtil.failedFuture(new IllegalStateException(
                    "Custom image renderer is not ready"));
        }
        return current.createEditorLoginUrl();
    }

    @Override
    public CompletableFuture<Map<String, Object>> resolveData(
            String templateId,
            List<ImageDataProviderSpec> requestedProviders,
            ImageRenderRequest request) {
        if (requestedProviders == null || requestedProviders.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.<String, Object>emptyMap());
        }
        if (requestedProviders.size() > customSettings.getMaximumProviderQueries()) {
            return FutureUtil.failedFuture(new IllegalArgumentException(
                    "Template requests too many data providers"));
        }
        final Map<String, Object> resolved = new ConcurrentHashMap<String, Object>();
        List<CompletableFuture<Void>> futures = new ArrayList<CompletableFuture<Void>>();
        for (ImageDataProviderSpec spec : requestedProviders) {
            if (spec == null) continue;
            final String id = normalizeProviderId(spec.getId());
            final ImageDataProvider provider = providers.get(id);
            if (provider == null) {
                return FutureUtil.failedFuture(new IllegalArgumentException(
                        "Template data provider is unavailable: " + id));
            }
            String cacheKey = cacheKey(templateId, spec, request);
            // Inventory data includes item PNGs and must recheck the requesting QQ's ownership.
            final boolean cacheable = !"inventory".equals(id);
            Map<String, Object> cached = cacheable ? readProviderCache(cacheKey) : null;
            if (cached != null) {
                resolved.put(id, cached);
                continue;
            }
            final ImageDataRequest dataRequest = new ImageDataRequest(
                    templateId, request.getContext(), spec.getOptions());
            final CompletableFuture<Map<String, Object>> provided;
            try {
                CompletableFuture<Map<String, Object>> candidate = provider.provide(dataRequest);
                provided = candidate == null
                        ? FutureUtil.<Map<String, Object>>failedFuture(
                                new IllegalStateException("Data provider returned no future: " + id))
                        : withTimeout(candidate, customSettings.getProviderTimeoutMs(), id);
            } catch (Throwable throwable) {
                return FutureUtil.failedFuture(throwable);
            }
            final String providerCacheKey = cacheKey;
            futures.add(provided.thenAccept(new java.util.function.Consumer<Map<String, Object>>() {
                @Override
                public void accept(Map<String, Object> values) {
                    Map<String, Object> safe = immutableMap(values);
                    resolved.put(id, safe);
                    if (cacheable) writeProviderCache(providerCacheKey, safe);
                }
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[futures.size()]))
                .thenApply(new java.util.function.Function<Void, Map<String, Object>>() {
                    @Override
                    public Map<String, Object> apply(Void ignored) {
                        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(resolved));
                    }
                });
    }

    @Override
    public void info(String message) {
        platform.info(message);
    }

    @Override
    public void warn(String message) {
        platform.warn(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        platform.error(message, throwable);
    }

    private ImageTemplateEngineSettings engineSettings() throws IOException {
        Path dataDirectory = platform.getDataDirectory().toAbsolutePath().normalize();
        Path templates = dataDirectory.resolve(customSettings.getTemplatesDirectory())
                .toAbsolutePath().normalize();
        if (!templates.startsWith(dataDirectory)) {
            throw new IOException("Custom image templates directory escapes the plugin directory");
        }
        return new ImageTemplateEngineSettings(
                templates,
                customSettings.getMaximumWidth(),
                customSettings.getMaximumHeight(),
                customSettings.getMaximumPixels(),
                customSettings.getMaximumLayers(),
                customSettings.getMaximumLoopItems(),
                customSettings.getMaximumAssetBytes(),
                customSettings.getMaximumTemplateAssetBytes(),
                customSettings.getRenderTimeoutMs(),
                customSettings.getRenderThreads(),
                customSettings.getMaximumQueuedRenders(),
                customSettings.isRemoteImagesEnabled(),
                customSettings.isEditorEnabled(),
                customSettings.getEditorBindAddress(),
                customSettings.getEditorPort(),
                customSettings.getEditorLoginSeconds(),
                customSettings.getEditorMaximumUploadBytes());
    }

    private void registerBuiltInProviders() {
        providers.put("shitbot", new ImageDataProvider() {
            @Override
            public String getId() { return "shitbot"; }

            @Override
            public CompletableFuture<Map<String, Object>> provide(ImageDataRequest request) {
                Map<String, Object> values = new LinkedHashMap<String, Object>();
                values.put("platform", platform.getPlatformName());
                values.put("version", platform.getPluginVersion());
                values.put("generated-at", Instant.now().toString());
                return CompletableFuture.completedFuture(values);
            }
        });
        providers.put("online-players", new ImageDataProvider() {
            @Override
            public String getId() { return "online-players"; }

            @Override
            public CompletableFuture<Map<String, Object>> provide(ImageDataRequest request) {
                return platform.captureOnlinePlayers().thenApply(
                        new java.util.function.Function<Map<String, List<String>>, Map<String, Object>>() {
                            @Override
                            public Map<String, Object> apply(Map<String, List<String>> snapshot) {
                                return onlineData(snapshot);
                            }
                });
            }
        });
        providers.put("player-avatar", new ImageDataProvider() {
            @Override
            public String getId() { return "player-avatar"; }

            @Override
            public CompletableFuture<Map<String, Object>> provide(ImageDataRequest request) {
                Map<String, Object> values = new LinkedHashMap<String, Object>();
                values.put("url-template", settings.getImage().getAvatarUrlTemplate());
                // Avatar layers bind each player at render time, including inside loops.
                // Keep the existing single-player provider contract for older templates.
                if (Boolean.parseBoolean(option(request, "template-only", "false"))) {
                    return CompletableFuture.completedFuture(values);
                }
                String player = bindContext(option(request, "player", ""), request.getContext()).trim();
                if (player.isEmpty()) {
                    return FutureUtil.failedFuture(new IllegalArgumentException(
                            "Player avatar provider requires a player"));
                }
                values.put("player", player);
                values.put("url", avatarUrl(player));
                return CompletableFuture.completedFuture(values);
            }
        });
        providers.put("papi", new ImageDataProvider() {
            @Override
            public String getId() { return "papi"; }

            @Override
            public CompletableFuture<Map<String, Object>> provide(ImageDataRequest request) {
                String player = option(request, "player", "");
                String server = option(request, "server", "");
                final Map<String, String> declarations = placeholderDeclarations(
                        request.getOptions().get("placeholders"));
                if (declarations.isEmpty()) {
                    return FutureUtil.failedFuture(new IllegalArgumentException(
                            "PAPI provider declares no placeholders"));
                }
                if (declarations.size() > customSettings.getMaximumProviderQueries()) {
                    return FutureUtil.failedFuture(new IllegalArgumentException(
                            "PAPI provider declares too many placeholders"));
                }
                final Map<String, String> boundDeclarations = new LinkedHashMap<String, String>();
                for (Map.Entry<String, String> declaration : declarations.entrySet()) {
                    boundDeclarations.put(declaration.getKey(),
                            bindContext(declaration.getValue(), request.getContext()));
                }
                final List<String> resolvedPlaceholders =
                        new ArrayList<String>(boundDeclarations.values());
                return platform.resolvePlaceholders(
                        bindContext(player, request.getContext()), resolvedPlaceholders,
                        bindContext(server, request.getContext())).thenApply(
                        new java.util.function.Function<Map<String, String>, Map<String, Object>>() {
                            @Override
                            public Map<String, Object> apply(Map<String, String> values) {
                                Map<String, Object> result = new LinkedHashMap<String, Object>();
                                Map<String, Object> resolved = new LinkedHashMap<String, Object>();
                                Map<String, Object> errors = new LinkedHashMap<String, Object>();
                                for (Map.Entry<String, String> declaration : boundDeclarations.entrySet()) {
                                    String placeholder = declaration.getValue();
                                    String value = values == null ? null : values.get(placeholder);
                                    if (value == null || value.equals(placeholder)) {
                                        errors.put(declaration.getKey(),
                                                "Placeholder was not resolved: " + placeholder);
                                    } else {
                                        resolved.put(declaration.getKey(), value);
                                        result.put(declaration.getKey(), value);
                                    }
                                }
                                result.put("values", resolved);
                                result.put("errors", errors);
                                return result;
                            }
                        });
            }
        });
        providers.put("player-profile", new ImageDataProvider() {
            @Override
            public String getId() { return "player-profile"; }

            @Override
            public CompletableFuture<Map<String, Object>> provide(ImageDataRequest request) {
                if (profileService == null) {
                    return FutureUtil.failedFuture(new IllegalStateException(
                            "Player profile provider is unavailable"));
                }
                String player = bindContext(option(request, "player", ""), request.getContext()).trim();
                if (player.isEmpty()) {
                    player = bindContext(String.valueOf(request.getContext().get("player")),
                            request.getContext()).trim();
                }
                if (player.isEmpty() || "null".equalsIgnoreCase(player)) {
                    return FutureUtil.failedFuture(new IllegalArgumentException(
                            "Player profile provider requires a player"));
                }
                final String requestedPlayer = player;
                return profileService.provideData(requestedPlayer);
            }
        });
        providers.put("inventory", new ImageDataProvider() {
            @Override
            public String getId() { return "inventory"; }

            @Override
            public CompletableFuture<Map<String, Object>> provide(ImageDataRequest request) {
                if (inventoryService == null) {
                    return FutureUtil.failedFuture(new IllegalStateException("Inventory provider is unavailable"));
                }
                String player = bindContext(option(request, "player", "${context.player}"), request.getContext()).trim();
                Object qq = request.getContext().get("qq");
                return inventoryService.provideTemplateData(player, qq == null ? "" : String.valueOf(qq));
            }
        });
    }

    private Map<String, Object> onlineData(Map<String, List<String>> snapshot) {
        List<Map<String, Object>> servers = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> allPlayers = new ArrayList<Map<String, Object>>();
        int total = 0;
        if (snapshot != null) {
            for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
                List<Map<String, Object>> players = new ArrayList<Map<String, Object>>();
                if (entry.getValue() != null) {
                    for (String name : entry.getValue()) {
                        if (name == null || name.trim().isEmpty()) continue;
                        Map<String, Object> player = new LinkedHashMap<String, Object>();
                        player.put("name", name.trim());
                        if (settings.getImage().isAvatarEnabled()) {
                            player.put("avatar", avatarUrl(name.trim()));
                        }
                        players.add(player);
                        allPlayers.add(player);
                    }
                }
                Map<String, Object> server = new LinkedHashMap<String, Object>();
                server.put("id", entry.getKey());
                server.put("name", entry.getKey());
                server.put("players", players);
                server.put("count", Integer.valueOf(players.size()));
                servers.add(server);
                total += players.size();
            }
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("group-by-server", Boolean.valueOf(platform.isProxy()));
        data.put("players", allPlayers);
        data.put("servers", servers);
        data.put("total", Integer.valueOf(total));
        return data;
    }

    private String avatarUrl(String player) {
        try {
            return settings.getImage().getAvatarUrlTemplate().replace(
                    "%player%", URLEncoder.encode(player, StandardCharsets.UTF_8.name()));
        } catch (Exception ignored) {
            return "";
        }
    }

    private <T> CompletableFuture<T> withTimeout(final CompletableFuture<T> source,
                                                  int timeoutMs,
                                                  final String providerId) {
        final CompletableFuture<T> result = new CompletableFuture<T>();
        final ScheduledFuture<?> timeout = timeoutExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                result.completeExceptionally(new java.util.concurrent.TimeoutException(
                        "Image data provider timed out: " + providerId));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        source.whenComplete(new java.util.function.BiConsumer<T, Throwable>() {
            @Override
            public void accept(T value, Throwable throwable) {
                timeout.cancel(false);
                if (throwable == null) result.complete(value);
                else result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    private String cacheKey(String templateId,
                            ImageDataProviderSpec spec,
                            ImageRenderRequest request) {
        return String.valueOf(templateId) + '\u0000' + spec.getId() + '\u0000'
                + spec.getOptions().toString() + '\u0000' + request.getContext().toString();
    }

    private Map<String, Object> readProviderCache(String key) {
        if (customSettings.getProviderCacheSeconds() <= 0) return null;
        synchronized (providerCache) {
            CachedProviderData cached = providerCache.get(key);
            if (cached == null) return null;
            if (cached.expiresAt <= System.currentTimeMillis()) {
                providerCache.remove(key);
                return null;
            }
            return cached.values;
        }
    }

    private void writeProviderCache(String key, Map<String, Object> values) {
        if (customSettings.getProviderCacheSeconds() <= 0) return;
        synchronized (providerCache) {
            providerCache.put(key, new CachedProviderData(values,
                    System.currentTimeMillis() + customSettings.getProviderCacheSeconds() * 1000L));
            while (providerCache.size() > MAX_PROVIDER_CACHE_ENTRIES) {
                providerCache.remove(providerCache.keySet().iterator().next());
            }
        }
    }

    private Map<String, Object> immutableMap(Map<String, Object> values) {
        return values == null || values.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }

    private String option(ImageDataRequest request, String name, String fallback) {
        Object value = request.getOptions().get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?>)) return Collections.emptyList();
        List<String> values = new ArrayList<String>();
        for (Object item : (Iterable<?>) value) {
            if (item != null && !String.valueOf(item).trim().isEmpty()) {
                values.add(String.valueOf(item).trim());
            }
        }
        return values;
    }

    private Map<String, String> placeholderDeclarations(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    String name = String.valueOf(entry.getKey()).trim();
                    String placeholder = String.valueOf(entry.getValue()).trim();
                    if (!name.isEmpty() && !placeholder.isEmpty()) result.put(name, placeholder);
                }
            }
            return result;
        }
        for (String placeholder : stringList(value)) result.put(placeholder, placeholder);
        return result;
    }

    private String bindContext(String value, Map<String, Object> context) {
        String result = value == null ? "" : value;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            result = result.replace("${context." + entry.getKey() + "}",
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }

    private String normalizeProviderId(String value) {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid image data provider ID: " + value);
        }
        return id;
    }

    private boolean isBuiltInProvider(String id) {
        return "shitbot".equals(id) || "online-players".equals(id)
                || "player-avatar".equals(id) || "papi".equals(id)
                || "player-profile".equals(id) || "inventory".equals(id);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeLoadedRenderer();
        componentExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
        providers.clear();
        synchronized (providerCache) {
            providerCache.clear();
        }
    }

    private synchronized void closeLoadedRenderer() {
        engine = null;
        RendererComponentLoader.LoadedRenderer loaded = loadedRenderer;
        loadedRenderer = null;
        if (loaded != null) loaded.close();
    }

    private static final class CachedProviderData {
        private final Map<String, Object> values;
        private final long expiresAt;

        private CachedProviderData(Map<String, Object> values, long expiresAt) {
            this.values = values;
            this.expiresAt = expiresAt;
        }
    }
}
