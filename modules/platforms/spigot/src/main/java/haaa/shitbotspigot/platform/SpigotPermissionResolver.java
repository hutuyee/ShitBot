package haaa.shitbotspigot.platform;

import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbotspigot.ShitBotSpigot;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

final class SpigotPermissionResolver {
    private final ShitBotSpigot plugin;
    private final SchedulerAdapter scheduler;

    SpigotPermissionResolver(ShitBotSpigot plugin, SchedulerAdapter scheduler) {
        this.plugin = plugin;
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
                        String cleanName = playerName.trim();
                        Player online = SpigotPlatformBridge.findPlayerByExactName(cleanName);
                        if (online != null) {
                            checks.add(checkOnline(online, permission));
                        } else {
                            checks.add(checkOfflineByName(cleanName, permission));
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

    private CompletableFuture<Boolean> checkOfflineByName(final String playerName,
                                                          final String permission) {
        ShitBotRuntime runtime = plugin.getRuntime();
        CompletableFuture<Optional<UUID>> identity = runtime == null
                ? CompletableFuture.completedFuture(Optional.<UUID>empty())
                : runtime.getBindingService().findUuidByPlayerName(playerName);
        return identity.thenCompose(uniqueId -> {
            final CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
            try {
                scheduler.executeGlobal(() -> {
                    try {
                        Player online = SpigotPlatformBridge.findPlayerByExactName(playerName);
                        CompletableFuture<Boolean> check = online != null
                                ? checkOnline(online, permission)
                                : checkOffline(findOfflinePlayer(playerName, uniqueId.orElse(null)),
                                        playerName, permission);
                        check.whenComplete((allowed, error) -> result.complete(
                                error == null && Boolean.TRUE.equals(allowed)));
                    } catch (Throwable error) {
                        result.complete(Boolean.FALSE);
                    }
                });
            } catch (Throwable error) {
                result.complete(Boolean.FALSE);
            }
            return result;
        });
    }

    private OfflinePlayer findOfflinePlayer(String playerName, UUID uniqueId) {
        if (uniqueId != null) {
            return Bukkit.getOfflinePlayer(uniqueId);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayer(playerName);
        if (playerName.equals(cached.getName())) {
            return cached;
        }
        if (!Bukkit.getOnlineMode()) {
            // The name cache may only retain aA; vanilla offline UUIDs retain Aa's spelling.
            UUID offlineId = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
            OfflinePlayer offline = Bukkit.getOfflinePlayer(offlineId);
            if (playerName.equals(offline.getName())) {
                return offline;
            }
        }
        return null;
    }

    private CompletableFuture<Boolean> checkOffline(final OfflinePlayer player,
                                                    final String playerName,
                                                    final String permission) {
        if (player == null || !playerName.equals(player.getName())) {
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
