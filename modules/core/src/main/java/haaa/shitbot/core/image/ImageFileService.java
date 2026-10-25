package haaa.shitbot.core.image;

import haaa.shitbot.api.ImageRenderResult;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.NamedThreadFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit local exports with bounded retained PNG bytes and no always-running worker. */
public final class ImageFileService implements AutoCloseable {
    private static final long MAXIMUM_PENDING_BYTES = 32L * 1024L * 1024L;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(8), new NamedThreadFactory("shitbot-image-file", true),
            new ThreadPoolExecutor.AbortPolicy());
    private final Set<CompletableFuture<Path>> pending = new HashSet<CompletableFuture<Path>>();
    private volatile boolean closed;
    private long pendingBytes;

    public CompletableFuture<Path> save(ImageRenderResult image, Path target) {
        if (image == null || target == null) {
            return FutureUtil.failedFuture(new IllegalArgumentException("Image and target are required"));
        }
        final Path destination = target.toAbsolutePath().normalize();
        if (destination.getFileName() == null) {
            return FutureUtil.failedFuture(new IllegalArgumentException("Target must be a file path"));
        }
        final CompletableFuture<Path> result = new CompletableFuture<Path>();
        synchronized (this) {
            if (closed) return FutureUtil.failedFuture(new IllegalStateException("Image file service is closed"));
            if (pendingBytes + image.getByteSize() > MAXIMUM_PENDING_BYTES) {
                return FutureUtil.failedFuture(new RejectedExecutionException(
                        "Pending image exports exceed the 32 MiB byte limit"));
            }
            pending.add(result);
            pendingBytes += image.getByteSize();
            try {
                executor.execute(() -> {
                    Path saved = null;
                    Throwable failure = null;
                    try {
                        saved = write(image, destination);
                    } catch (Throwable exception) {
                        failure = exception;
                    } finally {
                        release(result, image.getByteSize());
                    }
                    if (failure == null) result.complete(saved);
                    else result.completeExceptionally(failure);
                });
            } catch (RejectedExecutionException exception) {
                release(result, image.getByteSize());
                result.completeExceptionally(exception);
            }
        }
        // Caller cancellation must not free capacity while its file operation is still running.
        return result.thenApply(path -> path);
    }

    private synchronized void release(CompletableFuture<Path> result, int byteSize) {
        if (pending.remove(result)) pendingBytes -= byteSize;
    }

    private Path write(ImageRenderResult image, Path destination) throws IOException {
        ensureOpen();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".shitbot-image-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                image.writeTo(output);
            }
            ensureOpen();
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new IOException("Image file service is closed");
        }
    }

    @Override public void close() {
        final List<CompletableFuture<Path>> unfinished;
        synchronized (this) {
            if (closed) return;
            closed = true;
            unfinished = new ArrayList<CompletableFuture<Path>>(pending);
            pending.clear();
            pendingBytes = 0L;
        }
        executor.shutdownNow();
        IllegalStateException failure = new IllegalStateException("Image file service is closed");
        for (CompletableFuture<Path> result : unfinished) result.completeExceptionally(failure);
    }
}
