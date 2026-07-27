package haaa.shitbot.core.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class FutureUtil {
    private FutureUtil() {
    }

    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(throwable);
        return future;
    }
}
