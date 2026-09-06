package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageRenderRequest;
import haaa.shitbot.api.ImageRenderResult;
import haaa.shitbot.api.ImageTemplateInfo;
import haaa.shitbot.api.spi.ImageTemplateEngine;
import haaa.shitbot.api.spi.ImageTemplateEngineHost;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class RendererEngine implements ImageTemplateEngine {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final ImageTemplateEngineSettings settings;
    private final ImageTemplateEngineHost host;
    private final TemplateRepository repository;
    private final SecureImageLoader imageLoader;
    private final SceneRenderer renderer;
    private final ExecutorService managementExecutor = Executors.newSingleThreadExecutor(
            new RendererThreadFactory("management"));
    private final ThreadPoolExecutor renderExecutor;
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
            new RendererThreadFactory("timeout"));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private volatile EditorServer editor;
    private CompletableFuture<Void> startFuture;

    RendererEngine(ImageTemplateEngineSettings settings, ImageTemplateEngineHost host) {
        if (settings == null || host == null) throw new IllegalArgumentException("renderer settings and host are required");
        this.settings = settings;
        this.host = host;
        this.repository = new TemplateRepository(settings, host);
        this.imageLoader = new SecureImageLoader(settings);
        this.renderer = new SceneRenderer(settings, imageLoader);
        this.renderExecutor = new ThreadPoolExecutor(
                settings.getRenderThreads(), settings.getRenderThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(settings.getMaximumQueuedRenders()),
                new RendererThreadFactory("render"), new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public synchronized CompletableFuture<Void> startAsync() {
        if (startFuture != null) return startFuture;
        if (closed.get()) return failed(new IllegalStateException("Renderer engine is closed"));
        startFuture = CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    repository.initialize();
                    if (settings.isEditorEnabled()) {
                        EditorServer created = new EditorServer(settings, host, repository, RendererEngine.this);
                        created.start();
                        editor = created;
                    }
                    ready.set(true);
                } catch (IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }
        }, managementExecutor);
        return startFuture;
    }

    @Override
    public CompletableFuture<ImageRenderResult> render(final String templateId,
                                                        final ImageRenderRequest request) {
        if (!ready.get() || closed.get()) {
            return failed(new IllegalStateException("Renderer engine is not ready"));
        }
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<TemplateSnapshot>() {
            @Override
            public TemplateSnapshot get() {
                try {
                    return repository.loadPublished(templateId);
                } catch (IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }
        }, managementExecutor).thenCompose(
                new java.util.function.Function<TemplateSnapshot, CompletableFuture<ImageRenderResult>>() {
                    @Override
                    public CompletableFuture<ImageRenderResult> apply(final TemplateSnapshot snapshot) {
                        return resolveAndRender(snapshot, request == null
                                ? ImageRenderRequest.of(Collections.<String, Object>emptyMap()) : request);
                    }
                });
    }

    CompletableFuture<ImageRenderResult> renderDraft(final String templateId,
                                                      final ImageRenderRequest request) {
        if (!ready.get() || closed.get()) return failed(new IllegalStateException("Renderer engine is not ready"));
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<TemplateSnapshot>() {
            @Override
            public TemplateSnapshot get() {
                try {
                    return repository.loadDraft(templateId);
                } catch (IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }
        }, managementExecutor).thenCompose(
                new java.util.function.Function<TemplateSnapshot, CompletableFuture<ImageRenderResult>>() {
                    @Override
                    public CompletableFuture<ImageRenderResult> apply(TemplateSnapshot snapshot) {
                        return resolveAndRender(snapshot, request);
                    }
                });
    }

    private CompletableFuture<ImageRenderResult> resolveAndRender(
            final TemplateSnapshot snapshot,
            final ImageRenderRequest request) {
        return host.resolveData(snapshot.getInfo().getId(), snapshot.getInfo().getProviders(), request)
                .thenCompose(new java.util.function.Function<Map<String, Object>,
                        CompletableFuture<ImageRenderResult>>() {
                    @Override
                    public CompletableFuture<ImageRenderResult> apply(Map<String, Object> data) {
                        return submitRender(snapshot, request.withData(data));
                    }
                });
    }

    private CompletableFuture<ImageRenderResult> submitRender(final TemplateSnapshot snapshot,
                                                               final ImageRenderRequest request) {
        final CompletableFuture<ImageRenderResult> result = new CompletableFuture<ImageRenderResult>();
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.getRenderTimeoutMillis());
        final Future<?> task;
        try {
            task = renderExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        result.complete(renderer.render(snapshot, request, deadline));
                    } catch (Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            return failed(new IllegalStateException("Custom image render queue is full", exception));
        }
        final ScheduledFuture<?> timeout = timeoutExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (result.completeExceptionally(new java.util.concurrent.TimeoutException(
                        "Custom image render timed out"))) {
                    task.cancel(true);
                }
            }
        }, settings.getRenderTimeoutMillis(), TimeUnit.MILLISECONDS);
        result.whenComplete(new java.util.function.BiConsumer<ImageRenderResult, Throwable>() {
            @Override
            public void accept(ImageRenderResult ignored, Throwable throwable) {
                timeout.cancel(false);
            }
        });
        return result;
    }

    @Override
    public List<ImageTemplateInfo> getTemplates() {
        return ready.get() ? repository.getPublishedTemplates() : Collections.<ImageTemplateInfo>emptyList();
    }

    @Override
    public Optional<ImageTemplateInfo> getTemplate(String templateId) {
        return ready.get() ? repository.findInfo(templateId) : Optional.<ImageTemplateInfo>empty();
    }

    @Override
    public CompletableFuture<String> createEditorLoginUrl() {
        EditorServer current = editor;
        if (!settings.isEditorEnabled()) return failed(new IllegalStateException("Image template editor is disabled"));
        if (!ready.get() || current == null) return failed(new IllegalStateException("Image template editor is not ready"));
        return CompletableFuture.completedFuture(current.createLoginUrl());
    }

    private <T> CompletableFuture<T> failed(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(throwable);
        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ready.set(false);
        EditorServer current = editor;
        editor = null;
        if (current != null) current.close();
        managementExecutor.shutdownNow();
        renderExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
        imageLoader.close();
    }
}
