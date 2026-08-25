package haaa.shitbot.core.console;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class LuckPermsPermissionResolver {
    private static final String PROVIDER_CLASS_NAME = "net.luckperms.api.LuckPermsProvider";
    private static final MethodKey PROVIDER_GET = new MethodKey("get");
    private static final MethodKey GET_USER_MANAGER = new MethodKey("getUserManager");
    private static final MethodKey LOOKUP_UNIQUE_ID = new MethodKey("lookupUniqueId", String.class);
    private static final MethodKey LOAD_USER = new MethodKey("loadUser", UUID.class);
    private static final MethodKey GET_CACHED_DATA = new MethodKey("getCachedData");
    private static final MethodKey GET_PERMISSION_DATA = new MethodKey("getPermissionData");
    private static final MethodKey CHECK_PERMISSION = new MethodKey("checkPermission", String.class);
    private static final MethodKey AS_BOOLEAN = new MethodKey("asBoolean");
    private static final Map<ClassLoader, WeakReference<Class<?>>> PROVIDER_CLASSES =
            new WeakHashMap<ClassLoader, WeakReference<Class<?>>>();
    private static final ClassValue<ConcurrentMap<MethodKey, Method>> METHODS =
            new ClassValue<ConcurrentMap<MethodKey, Method>>() {
                @Override
                protected ConcurrentMap<MethodKey, Method> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<MethodKey, Method>();
                }
            };

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
            Class<?> providerClass = providerClass(classLoader);
            Object luckPerms = method(providerClass, PROVIDER_GET).invoke(null);
            userManager = method(luckPerms.getClass(), GET_USER_MANAGER).invoke(luckPerms);
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
            Object lookup = method(userManager.getClass(), LOOKUP_UNIQUE_ID).invoke(userManager, playerName);
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
            Object loading = method(userManager.getClass(), LOAD_USER).invoke(userManager, uniqueId);
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
            Object cachedData = method(user.getClass(), GET_CACHED_DATA).invoke(user);
            Object permissionData = method(cachedData.getClass(), GET_PERMISSION_DATA).invoke(cachedData);
            Object tristate = method(permissionData.getClass(), CHECK_PERMISSION).invoke(permissionData, permission);
            return Boolean.TRUE.equals(method(tristate.getClass(), AS_BOOLEAN).invoke(tristate));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> providerClass(ClassLoader classLoader) throws ClassNotFoundException {
        synchronized (PROVIDER_CLASSES) {
            WeakReference<Class<?>> reference = PROVIDER_CLASSES.get(classLoader);
            Class<?> providerClass = reference == null ? null : reference.get();
            if (providerClass == null) {
                providerClass = Class.forName(PROVIDER_CLASS_NAME, true, classLoader);
                PROVIDER_CLASSES.put(classLoader, new WeakReference<Class<?>>(providerClass));
            }
            return providerClass;
        }
    }

    private static Method method(Class<?> owner, MethodKey key) throws NoSuchMethodException {
        ConcurrentMap<MethodKey, Method> methods = METHODS.get(owner);
        Method cached = methods.get(key);
        if (cached != null) {
            return cached;
        }
        Method resolved = owner.getMethod(key.name, key.parameterTypes);
        Method previous = methods.putIfAbsent(key, resolved);
        return previous == null ? resolved : previous;
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

    private static final class MethodKey {
        private final String name;
        private final Class<?>[] parameterTypes;
        private final int hashCode;

        private MethodKey(String name, Class<?>... parameterTypes) {
            this.name = name;
            this.parameterTypes = parameterTypes == null
                    ? new Class<?>[0] : parameterTypes.clone();
            this.hashCode = 31 * name.hashCode() + Arrays.hashCode(this.parameterTypes);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MethodKey)) {
                return false;
            }
            MethodKey key = (MethodKey) other;
            return name.equals(key.name) && Arrays.equals(parameterTypes, key.parameterTypes);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
