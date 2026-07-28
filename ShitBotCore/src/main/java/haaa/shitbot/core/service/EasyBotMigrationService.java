package haaa.shitbot.core.service;

import haaa.shitbot.core.database.BindingRepository;
import haaa.shitbot.core.database.EasyBotMigrationResult;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbot.core.util.FutureUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Validates a local EasyBot database path and starts an asynchronous binding import. */
public final class EasyBotMigrationService {
    public static final String DEFAULT_FILE_NAME = "EasyBot.db";

    private final PlatformBridge platform;
    private final BindingRepository repository;
    private final AtomicBoolean running = new AtomicBoolean();

    public EasyBotMigrationService(PlatformBridge platform, BindingRepository repository) {
        this.platform = platform;
        this.repository = repository;
    }

    public CompletableFuture<EasyBotMigrationResult> migrate(String requestedFileName) {
        final Path sourcePath;
        try {
            sourcePath = resolveSourcePath(requestedFileName);
        } catch (IllegalArgumentException exception) {
            return FutureUtil.failedFuture(exception);
        }

        if (!Files.isRegularFile(sourcePath)) {
            return FutureUtil.failedFuture(new IllegalArgumentException(
                    "找不到 EasyBot 数据库文件: " + sourcePath.toAbsolutePath()));
        }
        if (!running.compareAndSet(false, true)) {
            return FutureUtil.failedFuture(new IllegalStateException("已有 EasyBot 迁移任务正在执行"));
        }

        final CompletableFuture<EasyBotMigrationResult> future;
        try {
            future = repository.importEasyBotBindings(sourcePath);
        } catch (Throwable throwable) {
            running.set(false);
            return FutureUtil.failedFuture(throwable);
        }
        future.whenComplete(new java.util.function.BiConsumer<EasyBotMigrationResult, Throwable>() {
            @Override
            public void accept(EasyBotMigrationResult result, Throwable throwable) {
                running.set(false);
            }
        });
        return future;
    }

    private Path resolveSourcePath(String requestedFileName) {
        String fileName = requestedFileName == null || requestedFileName.trim().isEmpty()
                ? DEFAULT_FILE_NAME
                : requestedFileName.trim();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".db")) {
            throw new IllegalArgumentException("迁移文件必须是 .db 文件");
        }

        Path relative;
        try {
            relative = Paths.get(fileName);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无效的数据库文件名", exception);
        }
        if (relative.isAbsolute() || relative.getNameCount() != 1
                || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("数据库文件必须直接放在插件目录中，只能填写文件名");
        }

        Path dataDirectory = platform.getDataDirectory().toAbsolutePath().normalize();
        Path sourcePath = dataDirectory.resolve(relative).normalize();
        if (!dataDirectory.equals(sourcePath.getParent())) {
            throw new IllegalArgumentException("数据库文件必须位于插件目录中");
        }
        return sourcePath;
    }
}
