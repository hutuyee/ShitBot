package haaa.shitbot.renderer;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class RendererThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    RendererThreadFactory(String role) {
        this.prefix = "shitbot-renderer-" + role + '-';
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
