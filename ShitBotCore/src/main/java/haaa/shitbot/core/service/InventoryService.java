package haaa.shitbot.core.service;

import haaa.shitbot.core.config.Settings;
import haaa.shitbot.core.database.BindingRecord;
import haaa.shitbot.core.database.BindingRepository;
import haaa.shitbot.core.database.InventorySnapshotRepository;
import haaa.shitbot.core.inventory.InventoryImageRenderer;
import haaa.shitbot.core.inventory.InventorySnapshot;
import haaa.shitbot.core.inventory.ItemIconResolver;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

/** Coordinates live capture, durable offline snapshots, bounded caches and PNG rendering. */
public final class InventoryService implements AutoCloseable {
    private final Settings.Inventory settings;
    private final PlatformBridge platform;
    private final BindingRepository bindingRepository;
    private final InventorySnapshotRepository snapshotRepository;
    private final ItemIconResolver iconResolver;
    private final InventoryImageRenderer renderer;
    private final ExecutorService renderExecutor;
    private final ExecutorService resourceExecutor;
    private final ConcurrentHashMap<String, SnapshotHolder> memorySnapshots =
            new ConcurrentHashMap<String, SnapshotHolder>();
    private final ConcurrentHashMap<String, CompletableFuture<InventoryQueryResult>> inFlightQueries =
            new ConcurrentHashMap<String, CompletableFuture<InventoryQueryResult>>();
    private final AtomicBoolean periodicCaptureRunning = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledFuture<?> captureTask;
    private volatile ScheduledFuture<?> cleanupTask;

    public InventoryService(Settings.Inventory settings,
                            PlatformBridge platform,
                            BindingRepository bindingRepository,
                            InventorySnapshotRepository snapshotRepository) {
        this.settings = settings;
        this.platform = platform;
        this.bindingRepository = bindingRepository;
        this.snapshotRepository = snapshotRepository;
        this.iconResolver = new ItemIconResolver(settings, platform);
        this.renderer = new InventoryImageRenderer(settings, iconResolver);
        this.renderExecutor = Executors.newFixedThreadPool(
                settings.getMaximumConcurrentRenders(),
                new NamedThreadFactory("shitbot-inventory-render", true));
        this.resourceExecutor = Executors.newSingleThreadExecutor(
                new NamedThreadFactory("shitbot-inventory-resources", true));
    }

    public void start(ScheduledExecutorService scheduler) {
        if (!settings.isEnabled() || closed.get()) {
            return;
        }
        iconResolver.prepareAsync(resourceExecutor).exceptionally(
                new java.util.function.Function<Throwable, Void>() {
                    @Override
                    public Void apply(Throwable throwable) {
                        platform.warn("Failed to warm up inventory icons: "
                                + FutureUtil.unwrap(throwable).getMessage());
                        return null;
                    }
                });
        captureTask = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                captureOnlineInventories();
            }
        }, 5L, settings.getSnapshotIntervalSeconds(), TimeUnit.SECONDS);
        cleanupTask = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                cleanupExpiredSnapshots();
            }
        }, 15L, 24L * 60L, TimeUnit.MINUTES);
    }

    /** Queries the automatically selected player ID bound to the supplied QQ number. */
    public CompletableFuture<InventoryQueryResult> queryForQq(final String qqId) {
        return queryForQq(qqId, null);
    }

    /**
     * Queries one explicitly requested player ID after verifying that it is
     * bound to the supplied QQ number. A blank player name keeps the legacy
     * automatic-selection behavior.
     */
    public CompletableFuture<InventoryQueryResult> queryForQq(final String qqId,
                                                               final String playerName) {
        if (!settings.isEnabled()) {
            return CompletableFuture.completedFuture(
                    InventoryQueryResult.status(InventoryQueryResult.Status.DISABLED));
        }
        if (closed.get()) {
            return FutureUtil.failedFuture(new IllegalStateException("Inventory service is closed"));
        }
        final String cleanQq = qqId == null ? "" : qqId.trim();
        final String cleanPlayer = playerName == null ? "" : playerName.trim();
        final String queryKey = cleanQq + '\u0000' + cleanPlayer;
        final CompletableFuture<InventoryQueryResult> created;
        synchronized (inFlightQueries) {
            CompletableFuture<InventoryQueryResult> existing = inFlightQueries.get(queryKey);
            if (existing != null) {
                return existing;
            }
            created = doQuery(cleanQq, cleanPlayer);
            inFlightQueries.put(queryKey, created);
        }
        created.whenComplete(new java.util.function.BiConsumer<InventoryQueryResult, Throwable>() {
            @Override
            public void accept(InventoryQueryResult result, Throwable throwable) {
                inFlightQueries.remove(queryKey, created);
            }
        });
        return created;
    }

    /** Persists a platform-thread snapshot without doing compression or SQL on that thread. */
    public CompletableFuture<Void> persistSnapshot(InventorySnapshot snapshot) {
        if (!settings.isEnabled() || closed.get() || snapshot == null) {
            return CompletableFuture.completedFuture(null);
        }
        return rememberAndPersist(Collections.singletonList(snapshot));
    }

    public CompletableFuture<Void> captureOnlineInventories() {
        if (!settings.isEnabled() || closed.get()
                || !periodicCaptureRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = platform.captureOnlineInventories()
                .thenCompose(new java.util.function.Function<List<InventorySnapshot>, CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> apply(List<InventorySnapshot> snapshots) {
                        return rememberAndPersist(snapshots);
                    }
                });
        result.whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
            @Override
            public void accept(Void ignored, Throwable throwable) {
                periodicCaptureRunning.set(false);
                if (throwable != null && !closed.get()) {
                    platform.warn("Failed to persist online inventory snapshots: "
                            + FutureUtil.unwrap(throwable).getMessage());
                }
            }
        });
        return result;
    }

    private CompletableFuture<InventoryQueryResult> doQuery(final String qqId,
                                                             final String requestedPlayer) {
        return bindingRepository.findAllByQqId(qqId).thenCompose(
                new java.util.function.Function<List<BindingRecord>, CompletableFuture<InventoryQueryResult>>() {
                    @Override
                    public CompletableFuture<InventoryQueryResult> apply(final List<BindingRecord> bindings) {
                        if (bindings == null || bindings.isEmpty()) {
                            return CompletableFuture.completedFuture(
                                    InventoryQueryResult.status(InventoryQueryResult.Status.NOT_BOUND));
                        }

                        final List<BindingRecord> selectedBindings;
                        if (requestedPlayer.isEmpty()) {
                            selectedBindings = bindings;
                        } else {
                            BindingRecord requestedBinding = null;
                            for (BindingRecord binding : bindings) {
                                if (requestedPlayer.equals(binding.getPlayerName())) {
                                    requestedBinding = binding;
                                    break;
                                }
                            }
                            if (requestedBinding == null) {
                                return CompletableFuture.completedFuture(
                                        InventoryQueryResult.status(
                                                InventoryQueryResult.Status.PLAYER_NOT_BOUND));
                            }
                            selectedBindings = Collections.singletonList(requestedBinding);
                        }

                        final List<String> names = new ArrayList<String>(selectedBindings.size());
                        for (BindingRecord binding : selectedBindings) {
                            names.add(binding.getPlayerName());
                        }
                        return captureBoundPlayers(names).thenCompose(
                                new java.util.function.Function<Map<String, InventorySnapshot>, CompletableFuture<InventoryQueryResult>>() {
                                    @Override
                                    public CompletableFuture<InventoryQueryResult> apply(Map<String, InventorySnapshot> liveSnapshots) {
                                        InventorySnapshot selectedLive = selectByBindingOrder(selectedBindings, liveSnapshots);
                                        if (selectedLive != null) {
                                            final InventorySnapshot snapshot = selectedLive;
                                            rememberAndPersist(new ArrayList<InventorySnapshot>(liveSnapshots.values()))
                                                    .whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
                                                        @Override
                                                        public void accept(Void ignored, Throwable throwable) {
                                                            if (throwable != null) {
                                                                platform.warn("Failed to cache live inventory snapshot: "
                                                                        + FutureUtil.unwrap(throwable).getMessage());
                                                            }
                                                        }
                                                    });
                                            return render(snapshot, true);
                                        }
                                        return findBestOffline(names).thenCompose(
                                                new java.util.function.Function<Optional<InventorySnapshot>, CompletableFuture<InventoryQueryResult>>() {
                                                    @Override
                                                    public CompletableFuture<InventoryQueryResult> apply(Optional<InventorySnapshot> snapshot) {
                                                        return snapshot.isPresent()
                                                                ? render(snapshot.get(), false)
                                                                : CompletableFuture.completedFuture(InventoryQueryResult.status(
                                                                InventoryQueryResult.Status.NO_SNAPSHOT));
                                                    }
                                                });
                                    }
                                });
                    }
                });
    }

    private CompletableFuture<Map<String, InventorySnapshot>> captureBoundPlayers(final List<String> names) {
        return platform.captureInventories(names).handle(
                new java.util.function.BiFunction<Map<String, InventorySnapshot>, Throwable, Map<String, InventorySnapshot>>() {
                    @Override
                    public Map<String, InventorySnapshot> apply(Map<String, InventorySnapshot> snapshots,
                                                                 Throwable throwable) {
                        if (throwable != null) {
                            platform.warn("Live inventory capture failed; using offline snapshot: "
                                    + FutureUtil.unwrap(throwable).getMessage());
                            return Collections.emptyMap();
                        }
                        return snapshots == null ? Collections.<String, InventorySnapshot>emptyMap() : snapshots;
                    }
                });
    }

    private CompletableFuture<Optional<InventorySnapshot>> findBestOffline(final List<String> names) {
        final InventorySnapshot memory = findFreshestMemory(names);
        return snapshotRepository.findFreshest(names).thenApply(
                new java.util.function.Function<Optional<InventorySnapshot>, Optional<InventorySnapshot>>() {
                    @Override
                    public Optional<InventorySnapshot> apply(Optional<InventorySnapshot> persisted) {
                        InventorySnapshot databaseSnapshot = persisted.orElse(null);
                        InventorySnapshot selected = newer(memory, databaseSnapshot);
                        long oldestAllowed = System.currentTimeMillis()
                                - settings.getSnapshotRetentionDays() * 24L * 60L * 60L * 1000L;
                        if (selected != null && selected.getCapturedAt() >= oldestAllowed) {
                            remember(selected);
                            return Optional.of(selected);
                        }
                        return Optional.empty();
                    }
                });
    }

    private CompletableFuture<InventoryQueryResult> render(final InventorySnapshot snapshot, final boolean live) {
        final CompletableFuture<Void> resourcesReady = iconResolver.prepareAsync(resourceExecutor);
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<InventoryQueryResult>() {
            @Override
            public InventoryQueryResult get() {
                try {
                    int waitMs = settings.getResourceIndexWaitMs();
                    if (waitMs > 0) {
                        try {
                            resourcesReady.get(waitMs, TimeUnit.MILLISECONDS);
                        } catch (java.util.concurrent.TimeoutException timeout) {
                            platform.warn("Inventory resource index is still loading; rendering available icons only");
                        } catch (java.util.concurrent.ExecutionException failure) {
                            platform.warn("Inventory resource index is unavailable: "
                                    + FutureUtil.unwrap(failure).getMessage());
                        }
                    }
                    return InventoryQueryResult.success(renderer.render(snapshot, live), snapshot, live);
                } catch (Exception exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }
        }, renderExecutor);
    }

    private CompletableFuture<Void> rememberAndPersist(List<InventorySnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<InventorySnapshot> valid = new ArrayList<InventorySnapshot>(snapshots.size());
        for (InventorySnapshot snapshot : snapshots) {
            if (snapshot != null) {
                remember(snapshot);
                valid.add(snapshot);
            }
        }
        pruneMemoryCache();
        return valid.isEmpty() ? CompletableFuture.completedFuture(null) : snapshotRepository.saveAll(valid);
    }

    private void remember(InventorySnapshot snapshot) {
        final String key = snapshot.getPlayerName();
        memorySnapshots.compute(key, new java.util.function.BiFunction<String, SnapshotHolder, SnapshotHolder>() {
            @Override
            public SnapshotHolder apply(String ignored, SnapshotHolder previous) {
                if (previous != null && previous.snapshot.getCapturedAt() > snapshot.getCapturedAt()) {
                    previous.lastAccess = System.currentTimeMillis();
                    return previous;
                }
                return new SnapshotHolder(snapshot);
            }
        });
    }

    private InventorySnapshot findFreshestMemory(List<String> names) {
        InventorySnapshot selected = null;
        for (String name : names) {
            SnapshotHolder holder = memorySnapshots.get(name);
            if (holder != null) {
                holder.lastAccess = System.currentTimeMillis();
                selected = newer(selected, holder.snapshot);
            }
        }
        return selected;
    }

    private InventorySnapshot selectByBindingOrder(List<BindingRecord> bindings,
                                                    Map<String, InventorySnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        for (BindingRecord binding : bindings) {
            InventorySnapshot snapshot = snapshots.get(binding.getPlayerName());
            if (snapshot != null) {
                return snapshot;
            }
        }
        return null;
    }

    private InventorySnapshot newer(InventorySnapshot left, InventorySnapshot right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.getCapturedAt() >= right.getCapturedAt() ? left : right;
    }

    private void pruneMemoryCache() {
        int maximum = settings.getMemoryMaximumEntries();
        if (memorySnapshots.size() <= maximum) {
            return;
        }
        List<Map.Entry<String, SnapshotHolder>> entries =
                new ArrayList<Map.Entry<String, SnapshotHolder>>(memorySnapshots.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, SnapshotHolder>>() {
            @Override
            public int compare(Map.Entry<String, SnapshotHolder> left,
                               Map.Entry<String, SnapshotHolder> right) {
                return Long.compare(left.getValue().lastAccess, right.getValue().lastAccess);
            }
        });
        int removeCount = Math.max(1, entries.size() - maximum);
        for (int i = 0; i < removeCount; i++) {
            Map.Entry<String, SnapshotHolder> entry = entries.get(i);
            memorySnapshots.remove(entry.getKey(), entry.getValue());
        }
    }

    private void cleanupExpiredSnapshots() {
        if (closed.get()) {
            return;
        }
        long cutoff = System.currentTimeMillis()
                - settings.getSnapshotRetentionDays() * 24L * 60L * 60L * 1000L;
        snapshotRepository.deleteOlderThan(cutoff).exceptionally(
                new java.util.function.Function<Throwable, Integer>() {
                    @Override
                    public Integer apply(Throwable throwable) {
                        platform.warn("Failed to clean old inventory snapshots: "
                                + FutureUtil.unwrap(throwable).getMessage());
                        return Integer.valueOf(0);
                    }
                });
        for (Map.Entry<String, SnapshotHolder> entry : memorySnapshots.entrySet()) {
            if (entry.getValue().snapshot.getCapturedAt() < cutoff) {
                memorySnapshots.remove(entry.getKey(), entry.getValue());
            }
        }
        pruneMemoryCache();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> currentCaptureTask = captureTask;
        if (currentCaptureTask != null) {
            currentCaptureTask.cancel(false);
        }
        ScheduledFuture<?> currentCleanupTask = cleanupTask;
        if (currentCleanupTask != null) {
            currentCleanupTask.cancel(false);
        }
        inFlightQueries.clear();
        memorySnapshots.clear();
        renderExecutor.shutdownNow();
        resourceExecutor.shutdownNow();
    }

    private static final class SnapshotHolder {
        private final InventorySnapshot snapshot;
        private volatile long lastAccess;

        private SnapshotHolder(InventorySnapshot snapshot) {
            this.snapshot = snapshot;
            this.lastAccess = System.currentTimeMillis();
        }
    }
}
