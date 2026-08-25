package haaa.shitbotspigot.platform;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

final class SpigotPermissionResolver {
    private final SchedulerAdapter scheduler;

    SpigotPermissionResolver(SchedulerAdapter scheduler) {
        this.scheduler = scheduler;
    }

    CompletableFuture<Boolean> hasPermission(final List<String> playerNames, final String permission) {
        if (permission == null || permission.trim().isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        final CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
        scheduler.executeGlobal(new Runnable() {
            @Override
            public void run() {
                List<CompletableFuture<Boolean>> checks = new ArrayList<CompletableFuture<Boolean>>();
                if (playerNames != null) {
                    for (String playerName : playerNames) {
                        if (playerName == null || playerName.trim().isEmpty()) {
                            continue;
                        }
                        Player online = Bukkit.getPlayerExact(playerName.trim());
                        if (online != null) {
                            checks.add(checkOnline(online, permission));
                        } else {
                            checks.add(checkOffline(Bukkit.getOfflinePlayer(playerName.trim()), permission));
                        }
                    }
                }
                completeAny(checks, result);
            }
        });
        return result;
    }

    private CompletableFuture<Boolean> checkOnline(final Player player, final String permission) {
        final CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
        scheduler.executeForPlayer(player, new Runnable() {
            @Override
            public void run() {
                try {
                    result.complete(Boolean.valueOf(player.isOnline() && player.hasPermission(permission)));
                } catch (Throwable throwable) {
                    result.complete(Boolean.FALSE);
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                result.complete(Boolean.FALSE);
            }
        });
        return result;
    }

    private CompletableFuture<Boolean> checkOffline(final OfflinePlayer player, final String permission) {
        if (player == null) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        try {
            if (player.isOp()) {
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        } catch (Throwable ignored) {
        }
        CompletableFuture<Boolean> luckPerms = checkLuckPerms(player.getUniqueId(), permission);
        if (luckPerms != null) {
            return luckPerms;
        }
        return CompletableFuture.completedFuture(Boolean.valueOf(checkVault(player, permission)));
    }

    private CompletableFuture<Boolean> checkLuckPerms(UUID uniqueId, final String permission) {
        if (uniqueId == null) {
            return null;
        }
        try {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (plugin == null) {
                return null;
            }
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider", true,
                    plugin.getClass().getClassLoader());
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            Object loading = userManager.getClass().getMethod("loadUser", UUID.class)
                    .invoke(userManager, uniqueId);
            if (!(loading instanceof CompletionStage)) {
                return null;
            }
            return ((CompletionStage<?>) loading).toCompletableFuture().handle(
                    new java.util.function.BiFunction<Object, Throwable, Boolean>() {
                        @Override
                        public Boolean apply(Object user, Throwable throwable) {
                            if (throwable != null || user == null) {
                                return Boolean.FALSE;
                            }
                            return Boolean.valueOf(readLuckPermsPermission(user, permission));
                        }
                    });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean readLuckPermsPermission(Object user, String permission) {
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

    private boolean checkVault(OfflinePlayer player, String permission) {
        try {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("Vault");
            if (plugin == null) {
                return false;
            }
            Class<?> permissionClass = Class.forName("net.milkbowl.vault.permission.Permission", true,
                    plugin.getClass().getClassLoader());
            Object registration = Bukkit.getServicesManager().getRegistration(permissionClass);
            if (registration == null) {
                return false;
            }
            Object provider = registration.getClass().getMethod("getProvider").invoke(registration);
            for (Method method : provider.getClass().getMethods()) {
                if (!"playerHas".equals(method.getName()) || method.getParameterTypes().length != 3) {
                    continue;
                }
                Class<?> playerType = method.getParameterTypes()[1];
                Object playerArgument = OfflinePlayer.class.isAssignableFrom(playerType)
                        ? player : player.getName();
                Object allowed = method.invoke(provider, null, playerArgument, permission);
                if (Boolean.TRUE.equals(allowed)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void completeAny(final List<CompletableFuture<Boolean>> checks,
                             final CompletableFuture<Boolean> result) {
        if (checks.isEmpty()) {
            result.complete(Boolean.FALSE);
            return;
        }
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
    }
}
