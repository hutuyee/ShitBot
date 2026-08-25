package haaa.shitbotspigot.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes tasks on the correct server thread on both classic Bukkit servers and Folia.
 *
 * <p>Folia replaces the single main thread with per-region threads, so the classic
 * {@link Bukkit#getScheduler()} API and {@link Bukkit#isPrimaryThread()} cannot be used
 * there (calling them throws). Folia classes are not on the compile-time classpath, because
 * the module must stay buildable against the 1.15 Spigot API and run on every Spigot/Paper
 * version from 1.8 upward. This adapter therefore detects Folia at runtime and dispatches
 * through its schedulers via reflection. On non-Folia servers every call behaves exactly
 * like the classic Bukkit scheduler, so a single jar keeps supporting every
 * Spigot/Paper/Folia version.</p>
 *
 * <p>Per-entity work on Folia is scheduled with the entity scheduler
 * ({@code Entity#getScheduler().execute(plugin, run, retired, delay)}); the region
 * scheduler only schedules by location, not by entity. The adapter looks every API up
 * defensively and falls back to the old-Folia region scheduler entity overload. Player
 * work is never moved to the global region thread because that thread does not own the
 * player entity.</p>
 */
final class SchedulerAdapter {
    /**
     * Folia marker classes: {@code RegionizedServer} existed until 1.20.4,
     * {@code ThreadedRegionization} from 1.20.4 onward.
     */
    private static final String[] FOLIA_MARKERS = {
            "io.papermc.paper.threadedregions.ThreadedRegionization",
            "io.papermc.paper.threadedregions.RegionizedServer"
    };

    private static final boolean FOLIA = detectFolia();
    private static final Runnable NOOP = new Runnable() {
        @Override
        public void run() {
        }
    };

    private final Plugin plugin;
    private final Method globalExecute;           // GlobalRegionScheduler.execute(Plugin, Runnable)
    private final Method globalRunAtFixedRate;    // GlobalRegionScheduler.runAtFixedRate(Plugin, Runnable, long, long)
    private final Method regionEntityExecute;     // RegionScheduler.execute(Plugin, Entity, Runnable) on old Folia
    private final Method entitySchedulerGetter;   // Entity.getScheduler()
    private final Method entitySchedulerExecute;  // EntityScheduler.execute(Plugin, Runnable, Runnable, long)
    private final Method entitySchedulerOwned;    // EntityScheduler.isOwnedByCurrentRegion() where present
    private final Method globalTickCheck;         // Bukkit.isGlobalTickThread()
    private final Method ownedRegionCheck;        // Bukkit.isOwnedByCurrentRegion(Entity)
    private final Object globalScheduler;
    private final Object regionScheduler;
    private final AtomicBoolean entityCompatibilityWarningLogged = new AtomicBoolean();

    private SchedulerAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
        if (FOLIA) {
            Method globalGetter = staticMethodOrNull("getGlobalRegionScheduler");
            Method regionGetter = staticMethodOrNull("getRegionScheduler");
            this.globalTickCheck = staticMethodOrNull("isGlobalTickThread");
            this.ownedRegionCheck = staticMethodOrNull("isOwnedByCurrentRegion", Entity.class);
            this.globalScheduler = globalGetter == null ? null : invokeSafe(globalGetter, null);
            this.regionScheduler = regionGetter == null ? null : invokeSafe(regionGetter, null);
            this.globalExecute = globalScheduler == null ? null : executeMethod(globalScheduler.getClass(), 2);
            Method fixedRate = globalScheduler == null
                    ? null : methodOrNull(globalScheduler.getClass(), "runAtFixedRate",
                    Plugin.class, Consumer.class, long.class, long.class);
            if (fixedRate == null && globalScheduler != null) {
                fixedRate = methodOrNull(globalScheduler.getClass(), "runAtFixedRate",
                        Plugin.class, Runnable.class, long.class, long.class);
            }
            this.globalRunAtFixedRate = fixedRate;
            this.regionEntityExecute = regionScheduler == null ? null : executeMethod(regionScheduler.getClass(), 3);
            this.entitySchedulerGetter = methodOrNull(Entity.class, "getScheduler");
            if (entitySchedulerGetter != null) {
                Class<?> schedulerType = entitySchedulerGetter.getReturnType();
                this.entitySchedulerExecute = executeMethod(schedulerType, 4);
                this.entitySchedulerOwned = methodOrNull(schedulerType, "isOwnedByCurrentRegion");
            } else {
                this.entitySchedulerExecute = null;
                this.entitySchedulerOwned = null;
            }
        } else {
            this.globalExecute = null;
            this.globalRunAtFixedRate = null;
            this.regionEntityExecute = null;
            this.entitySchedulerGetter = null;
            this.entitySchedulerExecute = null;
            this.entitySchedulerOwned = null;
            this.globalTickCheck = null;
            this.ownedRegionCheck = null;
            this.globalScheduler = null;
            this.regionScheduler = null;
        }
    }

    static SchedulerAdapter forPlugin(JavaPlugin plugin) {
        return new SchedulerAdapter(plugin);
    }

    boolean isFolia() {
        return FOLIA;
    }

    /**
     * Runs the task on the global server thread: the primary thread on classic Bukkit
     * servers, the global region thread on Folia. Runs inline when already there, because
     * Folia forbids scheduling the global region scheduler from the global region thread.
     */
    void executeGlobal(Runnable task) {
        if (task == null) {
            return;
        }
        if (isGlobalTickThread()) {
            task.run();
            return;
        }
        if (FOLIA && globalExecute != null && globalScheduler != null) {
            invoke(globalExecute, globalScheduler, plugin, task);
            return;
        }
        // Non-Folia, or a Folia fork missing the global scheduler API: classic path.
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Runs the task on the thread that owns the given player: the primary thread on
     * classic Bukkit servers, the player's region thread on Folia. Runs inline when the
     * current thread already owns the player, because Folia forbids scheduling a task
     * onto the region the current thread itself owns.
     */
    void executeForPlayer(Player player, Runnable task) {
        executeForPlayer(player, task, NOOP);
    }

    /**
     * Runs either {@code task} on the player's owning thread or {@code retired} when the
     * player can no longer be scheduled. The two callbacks are guarded so at most one can
     * run, including Folia versions that invoke the retired callback while returning false.
     */
    void executeForPlayer(Player player, Runnable task, Runnable retired) {
        final Runnable retiredCallback = retired == null ? NOOP : retired;
        if (task == null || player == null) {
            retiredCallback.run();
            return;
        }
        final AtomicBoolean claimed = new AtomicBoolean();
        final Runnable guardedTask = once(claimed, task);
        final Runnable guardedRetired = once(claimed, retiredCallback);
        if (!FOLIA) {
            executeGlobal(guardedTask);
            return;
        }
        boolean owned = false;
        try {
            owned = isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            // Continue through the entity scheduler when an ownership probe is unavailable.
        }
        if (owned) {
            guardedTask.run();
            return;
        }
        // Modern Folia: entity scheduler (Entity#getScheduler).
        Object entityScheduler = entitySchedulerGetter == null ? null : invokeSafe(entitySchedulerGetter, player);
        if (entityScheduler != null && entitySchedulerExecute != null) {
            Object scheduled = invokeSafe(entitySchedulerExecute, entityScheduler,
                    plugin, guardedTask, guardedRetired, Long.valueOf(0L));
            if (Boolean.TRUE.equals(scheduled)) {
                return;
            }
            if (Boolean.FALSE.equals(scheduled)) {
                guardedRetired.run();
                return;
            }
        }
        // Very old Folia: region scheduler entity overload.
        if (regionEntityExecute != null && regionScheduler != null) {
            try {
                invoke(regionEntityExecute, regionScheduler, plugin, player, guardedTask);
                return;
            } catch (Throwable ignored) {
                // Report once below and complete through the failure callback.
            }
        }
        if (entityCompatibilityWarningLogged.compareAndSet(false, true)) {
            plugin.getLogger().warning(
                    "Folia entity scheduler is unavailable; player-owned work will be skipped safely.");
        }
        guardedRetired.run();
    }

    private static Runnable once(final AtomicBoolean claimed, final Runnable callback) {
        return new Runnable() {
            @Override
            public void run() {
                if (claimed.compareAndSet(false, true)) {
                    callback.run();
                }
            }
        };
    }

    void runGlobalAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        if (task == null) {
            return;
        }
        if (FOLIA && globalRunAtFixedRate != null && globalScheduler != null) {
            Object scheduledTask = Consumer.class.isAssignableFrom(
                    globalRunAtFixedRate.getParameterTypes()[1])
                    ? new Consumer<Object>() {
                        @Override
                        public void accept(Object ignored) {
                            task.run();
                        }
                    }
                    : task;
            invoke(globalRunAtFixedRate, globalScheduler, plugin, scheduledTask,
                    Long.valueOf(Math.max(1L, initialDelayTicks)),
                    Long.valueOf(Math.max(1L, periodTicks)));
            return;
        }
        if (FOLIA) {
            return;
        }
        Bukkit.getScheduler().runTaskTimer(plugin, task,
                Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
    }

    /** True when the current thread may touch global/primary-thread-only state. */
    boolean isGlobalTickThread() {
        if (FOLIA && globalTickCheck != null) {
            return Boolean.TRUE.equals(invoke(globalTickCheck, null));
        }
        return Bukkit.isPrimaryThread();
    }

    /** True when the current thread is allowed to touch the given player directly. */
    boolean isOwnedByCurrentRegion(Player player) {
        if (player == null) {
            return false;
        }
        if (FOLIA) {
            if (ownedRegionCheck != null) {
                return Boolean.TRUE.equals(invoke(ownedRegionCheck, null, player));
            }
            Object entityScheduler = entitySchedulerGetter == null ? null : invokeSafe(entitySchedulerGetter, player);
            if (entityScheduler != null && entitySchedulerOwned != null) {
                return Boolean.TRUE.equals(invoke(entitySchedulerOwned, entityScheduler));
            }
            return false;
        }
        return Bukkit.isPrimaryThread();
    }

    /**
     * Finds an {@code execute} overload whose parameters are compatible with the given
     * parameter count and slot types (assignable from the type we plan to pass). This is
     * deliberately lenient: Folia has used Plugin/Entity/Runnable (old region scheduler),
     * Plugin/Runnable/Runnable/long (EntityScheduler) and Plugin/Runnable (global) across
     * versions, and entity-based region scheduling was removed entirely in newer versions.
     */
    private static Method executeMethod(Class<?> owner, int paramCount) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals("execute")) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != paramCount) {
                continue;
            }
            if (!isAssignableFrom(params[0], Plugin.class)) {
                continue;
            }
            if (paramCount == 2) {
                if (!isAssignableFrom(params[1], Runnable.class)) {
                    continue;
                }
            } else if (paramCount == 3) {
                if (!isAssignableFrom(params[1], Entity.class) || !isAssignableFrom(params[2], Runnable.class)) {
                    continue;
                }
            } else if (paramCount == 4) {
                if (!isAssignableFrom(params[1], Runnable.class) || !isAssignableFrom(params[2], Runnable.class)) {
                    continue;
                }
                if (params[3] != long.class && params[3] != Long.class) {
                    continue;
                }
            }
            return method;
        }
        return null;
    }

    private static boolean isAssignableFrom(Class<?> declared, Class<?> passed) {
        return declared == passed || declared.isAssignableFrom(passed);
    }

    private static Method staticMethodOrNull(String name, Class<?>... parameterTypes) {
        try {
            return Bukkit.class.getMethod(name, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method methodOrNull(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeSafe(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Reflective Folia scheduler call failed", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Reflective Folia scheduler call failed", cause);
        }
    }

    private static boolean detectFolia() {
        for (String marker : FOLIA_MARKERS) {
            try {
                Class.forName(marker, false, SchedulerAdapter.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {
                // Try the next marker; if none exists this is a classic Bukkit server.
            }
        }
        return false;
    }
}
