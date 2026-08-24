package haaa.shitbot.core.console;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

public final class LuckPermsPermissionResolver {
    private LuckPermsPermissionResolver() {
    }

    public static CompletableFuture<Boolean> hasPermission(ClassLoader classLoader,
                                                           List<String> playerNames,
                                                           String permission) {
        if (classLoader == null || playerNames == null || playerNames.isEmpty()
                || permission == null || permission.trim().isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        final Object userManager;
        try {
            Class<?> providerClass = Class.forName(
                    "net.luckperms.api.LuckPermsProvider", true, classLoader);
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
        } catch (Throwable ignored) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }

        List<CompletableFuture<Boolean>> checks = new ArrayList<CompletableFuture<Boolean>>();
        for (String playerName : playerNames) {
            if (playerName != null && !playerName.trim().isEmpty()) {
                checks.add(checkPlayer(userManager, playerName.trim(), permission));
            }
        }
        return completeAny(checks);
    }

    private static CompletableFuture<Boolean> checkPlayer(final Object userManager,
                                                           String playerName,
                                                           final String permission) {
        final CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
        try {
            Object lookup = userManager.getClass().getMethod("lookupUniqueId", String.class)
                    .invoke(userManager, playerName);
            if (!(lookup instanceof CompletionStage)) {
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            ((CompletionStage<?>) lookup).whenComplete(
                    new java.util.function.BiConsumer<Object, Throwable>() {
                        @Override
                        public void accept(Object uniqueId, Throwable lookupFailure) {
                            if (lookupFailure != null || !(uniqueId instanceof UUID)) {
                                result.complete(Boolean.FALSE);
                                return;
                            }
                            loadUser(userManager, (UUID) uniqueId, permission, result);
                        }
                    });
        } catch (Throwable ignored) {
            result.complete(Boolean.FALSE);
        }
        return result;
    }

    private static void loadUser(Object userManager,
                                 UUID uniqueId,
                                 final String permission,
                                 final CompletableFuture<Boolean> result) {
        try {
            Object loading = userManager.getClass().getMethod("loadUser", UUID.class)
                    .invoke(userManager, uniqueId);
            if (!(loading instanceof CompletionStage)) {
                result.complete(Boolean.FALSE);
                return;
            }
            ((CompletionStage<?>) loading).whenComplete(
                    new java.util.function.BiConsumer<Object, Throwable>() {
                        @Override
                        public void accept(Object user, Throwable loadFailure) {
                            result.complete(Boolean.valueOf(loadFailure == null
                                    && user != null
                                    && readPermission(user, permission)));
                        }
                    });
        } catch (Throwable ignored) {
            result.complete(Boolean.FALSE);
        }
    }

    private static boolean readPermission(Object user, String permission) {
        try {
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
            Object tristate = permissionData.getClass().getMethod("checkPermission", String.class)
                    .invoke(permissionData, permission);
            return Boolean.TRUE.equals(tristate.getClass().getMethod("asBoolean").invoke(tristate));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static CompletableFuture<Boolean> completeAny(List<CompletableFuture<Boolean>> checks) {
        if (checks.isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        final CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
        final AtomicInteger remaining = new AtomicInteger(checks.size());
        for (CompletableFuture<Boolean> check : checks) {
            check.whenComplete(new java.util.function.BiConsumer<Boolean, Throwable>() {
                    @Override
                    public void accept(Boolean allowed, Throwable throwable) {
                        if (throwable == null && Boolean.TRUE.equals(allowed)) {
                            result.complete(Boolean.TRUE);
                        } else if (remaining.decrementAndGet() == 0) {
                            result.complete(Boolean.FALSE);
                        }
                    }
                });
        }
        return result;
    }
}
