package haaa.shitbotspigot.platform;

import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.inventory.InventorySnapshot;
import haaa.shitbot.core.platform.PlatformBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpigotPlatformBridge implements PlatformBridge {
    private static final Pattern TEXTURE_URL = Pattern.compile(
            "(?i)textures\\.minecraft\\.net/texture/([0-9a-f]{32,128})");
    private static final Pattern TEXTURE_HASH = Pattern.compile("(?i)^[0-9a-f]{32,128}$");
    private static final int MAX_PROFILE_PROPERTY_LENGTH = 32768;

    private final JavaPlugin plugin;
    private final SpigotItemIdentityResolver itemIdentityResolver;
    private final SchedulerAdapter scheduler;
    private final SpigotConsoleController consoleController;

    public SpigotPlatformBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemIdentityResolver = new SpigotItemIdentityResolver();
        this.scheduler = SchedulerAdapter.forPlugin(plugin);
        this.consoleController = new SpigotConsoleController(plugin, scheduler);
    }

    @Override
    public Path getDataDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public String getPlatformName() {
        return "Spigot";
    }

    @Override
    public CompletableFuture<ConsoleResult> executeConsoleRequest(ConsoleRequest request) {
        return consoleController.execute(request);
    }

    public void configureConsole(ConsoleSettings settings, boolean backendMode) throws java.io.IOException {
        consoleController.configureBackendListener(backendMode && settings != null
                ? settings.getBackendTransport().getListener() : null);
    }

    public void close() {
        consoleController.close();
    }

    @Override
    public CompletableFuture<Map<String, List<String>>> captureOnlinePlayers() {
        final CompletableFuture<Map<String, List<String>>> future = new CompletableFuture<Map<String, List<String>>>();
        Runnable capture = new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> players = new ArrayList<String>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player != null) {
                            players.add(player.getName());
                        }
                    }
                    Map<String, List<String>> snapshot = new LinkedHashMap<String, List<String>>();
                    if (!players.isEmpty()) {
                        snapshot.put(Bukkit.getServer().getName(), players);
                    }
                    future.complete(snapshot);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        };
        runOnPrimaryThread(capture);
        return future;
    }

    @Override
    public CompletableFuture<List<InventorySnapshot>> captureOnlineInventories() {
        final CompletableFuture<List<InventorySnapshot>> future =
                new CompletableFuture<List<InventorySnapshot>>();
        if (scheduler.isFolia()) {
            captureOnlineInventoriesFolia(future);
            return future;
        }
        runOnPrimaryThread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<InventorySnapshot> snapshots = new ArrayList<InventorySnapshot>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player != null && player.isOnline()) {
                            snapshots.add(snapshot(player));
                        }
                    }
                    future.complete(snapshots);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Map<String, InventorySnapshot>> captureInventories(List<String> playerNames) {
        final Set<String> requested = new LinkedHashSet<String>();
        if (playerNames != null) {
            for (String playerName : playerNames) {
                if (playerName != null && !playerName.trim().isEmpty()) {
                    requested.add(playerName.trim());
                }
            }
        }
        if (requested.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Collections.<String, InventorySnapshot>emptyMap());
        }
        final CompletableFuture<Map<String, InventorySnapshot>> future =
                new CompletableFuture<Map<String, InventorySnapshot>>();
        if (scheduler.isFolia()) {
            captureInventoriesFolia(requested, future);
            return future;
        }
        runOnPrimaryThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, InventorySnapshot> result =
                            new LinkedHashMap<String, InventorySnapshot>();
                    for (String playerName : requested) {
                        Player player = Bukkit.getPlayerExact(playerName);
                        if (player != null && player.isOnline()) {
                            result.put(playerName, snapshot(player));
                        }
                    }
                    future.complete(result);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        });
        return future;
    }

    /**
     * Takes a synchronous snapshot for Bukkit events that still expose the Player object.
     * Must run on the thread that owns the player: the primary thread on classic Bukkit
     * servers, the player's region thread on Folia.
     */
    public InventorySnapshot captureInventorySnapshot(Player player) {
        if (player != null && !scheduler.isOwnedByCurrentRegion(player)) {
            throw new IllegalStateException(
                    "Inventory snapshots must be captured on the thread that owns the player "
                            + "(the player's region thread on Folia, the primary thread otherwise)");
        }
        return player == null ? null : snapshot(player);
    }

    /** Folia: each player's inventory can only be read on that player's own region thread. */
    private void captureOnlineInventoriesFolia(
            final CompletableFuture<List<InventorySnapshot>> future) {
        final List<Player> online = new ArrayList<Player>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null) {
                online.add(player);
            }
        }
        if (online.isEmpty()) {
            future.complete(new ArrayList<InventorySnapshot>());
            return;
        }
        final InventorySnapshot[] ordered = new InventorySnapshot[online.size()];
        final AtomicInteger remaining = new AtomicInteger(online.size());
        for (int i = 0; i < online.size(); i++) {
            final int index = i;
            final Player player = online.get(i);
            final Runnable completion = new Runnable() {
                @Override
                public void run() {
                    synchronized (ordered) {
                        if (remaining.decrementAndGet() == 0) {
                            List<InventorySnapshot> result = new ArrayList<InventorySnapshot>();
                            for (InventorySnapshot item : ordered) {
                                if (item != null) {
                                    result.add(item);
                                }
                            }
                            future.complete(result);
                        }
                    }
                }
            };
            scheduler.executeForPlayer(player, new Runnable() {
                @Override
                public void run() {
                    InventorySnapshot captured = null;
                    try {
                        if (player.isOnline()) {
                            captured = snapshot(player);
                        }
                    } catch (Throwable throwable) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to capture inventory of " + player.getName(), throwable);
                    } finally {
                        synchronized (ordered) {
                            ordered[index] = captured;
                        }
                        completion.run();
                    }
                }
            }, completion);
        }
    }

    /** Folia: capture only the requested online players, each on its own region thread. */
    private void captureInventoriesFolia(final Set<String> requested,
            final CompletableFuture<Map<String, InventorySnapshot>> future) {
        final List<String> names = new ArrayList<String>(requested);
        final Map<String, InventorySnapshot> result =
                new LinkedHashMap<String, InventorySnapshot>();
        final AtomicInteger remaining = new AtomicInteger(names.size());
        for (final String playerName : names) {
            final Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                if (remaining.decrementAndGet() == 0) {
                    synchronized (result) {
                        future.complete(new LinkedHashMap<String, InventorySnapshot>(result));
                    }
                }
                continue;
            }
            final Runnable completion = new Runnable() {
                @Override
                public void run() {
                    synchronized (result) {
                        if (remaining.decrementAndGet() == 0) {
                            future.complete(new LinkedHashMap<String, InventorySnapshot>(result));
                        }
                    }
                }
            };
            scheduler.executeForPlayer(player, new Runnable() {
                @Override
                public void run() {
                    InventorySnapshot captured = null;
                    try {
                        if (player.isOnline()) {
                            captured = snapshot(player);
                        }
                    } catch (Throwable throwable) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to capture inventory of " + playerName, throwable);
                    } finally {
                        if (captured != null) {
                            synchronized (result) {
                                result.put(playerName, captured);
                            }
                        }
                        completion.run();
                    }
                }
            }, completion);
        }
    }

    private InventorySnapshot snapshot(Player player) {
        PlayerInventory inventory = player.getInventory();
        List<InventorySnapshot.Item> items = new ArrayList<InventorySnapshot.Item>();
        ItemStack[] contents = inventory.getContents();
        int storageLength = Math.min(InventorySnapshot.STORAGE_SIZE, contents == null ? 0 : contents.length);
        for (int slot = 0; slot < storageLength; slot++) {
            addItem(items, slot, contents[slot]);
        }

        ItemStack[] armor = inventory.getArmorContents();
        if (armor != null) {
            if (armor.length > 0) addItem(items, InventorySnapshot.BOOTS_SLOT, armor[0]);
            if (armor.length > 1) addItem(items, InventorySnapshot.LEGGINGS_SLOT, armor[1]);
            if (armor.length > 2) addItem(items, InventorySnapshot.CHESTPLATE_SLOT, armor[2]);
            if (armor.length > 3) addItem(items, InventorySnapshot.HELMET_SLOT, armor[3]);
        }
        addItem(items, InventorySnapshot.OFFHAND_SLOT, readOffhand(inventory));
        return new InventorySnapshot(
                player.getName(),
                player.getUniqueId() == null ? "" : player.getUniqueId().toString(),
                Bukkit.getServer().getName(),
                System.currentTimeMillis(),
                items);
    }

    private void addItem(List<InventorySnapshot.Item> items, int slot, ItemStack stack) {
        if (stack == null || stack.getType() == null || stack.getAmount() <= 0) return;
        Material material = stack.getType();
        String registryId = itemIdentityResolver.resolve(stack);
        // Some hybrid implementations expose an unknown Mod stack as Bukkit AIR.
        // Only discard it when the underlying NMS registry also says minecraft:air.
        if (material == Material.AIR && "minecraft:air".equals(registryId)) return;
        String materialName = material == Material.AIR
                ? registryPath(registryId).toUpperCase(Locale.ROOT) : material.name();
        ItemMeta meta = null;
        try {
            meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        } catch (Throwable ignored) {
            // A broken third-party ItemMeta must not abort the whole player's snapshot.
        }
        String displayName = humanize(materialName);
        if (meta != null) {
            try {
                if (meta.hasDisplayName()) {
                    String customName = ChatColor.stripColor(meta.getDisplayName());
                    if (customName != null && !customName.trim().isEmpty()) {
                        displayName = customName.trim();
                    }
                }
            } catch (Throwable ignored) {
                // Keep the material-derived name.
            }
        }
        ModelProperties model = readModelProperties(meta);
        int itemDamage = damage(stack, meta);
        String profileTextureHash = canHaveProfileTexture(materialName, registryId)
                ? readProfileTexture(meta) : "";
        items.add(new InventorySnapshot.Item(
                slot,
                registryId,
                materialName,
                displayName,
                stack.getAmount(),
                itemDamage,
                Math.max(0, material.getMaxDurability()),
                model.legacyInteger,
                model.itemModel,
                model.floats,
                model.flags,
                model.strings,
                model.colors,
                profileTextureHash,
                hasEnchantments(stack),
                booleanMethod(meta, "isUnbreakable")));
    }

    private boolean hasEnchantments(ItemStack stack) {
        try {
            return stack != null && !stack.getEnchantments().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String registryPath(String registryId) {
        if (registryId == null) return "unknown";
        int colon = registryId.indexOf(':');
        String path = colon < 0 ? registryId : registryId.substring(colon + 1);
        return path.replace('/', '_').replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private int damage(ItemStack stack, ItemMeta meta) {
        if (meta != null) {
            try {
                Method getDamage = meta.getClass().getMethod("getDamage");
                Object value = getDamage.invoke(meta);
                if (value instanceof Number) {
                    return Math.max(0, ((Number) value).intValue());
                }
            } catch (Throwable ignored) {
                // Pre-1.13 durability is stored on ItemStack.
            }
        }
        try {
            return Math.max(0, stack.getDurability());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private ModelProperties readModelProperties(ItemMeta meta) {
        ModelProperties result = new ModelProperties();
        if (meta == null) return result;
        result.legacyInteger = legacyCustomModelData(meta);
        result.itemModel = namespacedValue(invokeNoArg(meta, "getItemModel"));

        Object component = invokeNoArg(meta, "getCustomModelDataComponent");
        if (component != null) {
            result.floats = numberList(invokeNoArg(component, "getFloats"));
            result.flags = booleanList(invokeNoArg(component, "getFlags"));
            result.strings = stringList(invokeNoArg(component, "getStrings"));
            result.colors = colorList(invokeNoArg(component, "getColors"));
            if (result.legacyInteger == null && !result.floats.isEmpty()) {
                float first = result.floats.get(0).floatValue();
                if (!Float.isNaN(first) && !Float.isInfinite(first)) {
                    result.legacyInteger = Integer.valueOf(Math.round(first));
                }
            }
        }
        return result;
    }

    private Integer legacyCustomModelData(ItemMeta meta) {
        try {
            Method has = meta.getClass().getMethod("hasCustomModelData");
            if (!Boolean.TRUE.equals(has.invoke(meta))) return null;
            Method get = meta.getClass().getMethod("getCustomModelData");
            Object value = get.invoke(meta);
            return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String namespacedValue(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? text : "";
    }

    private List<Float> numberList(Object value) {
        List<Float> result = new ArrayList<Float>();
        if (!(value instanceof Iterable<?>)) return result;
        for (Object entry : (Iterable<?>) value) {
            if (result.size() >= 16) break;
            if (entry instanceof Number) {
                float number = ((Number) entry).floatValue();
                if (!Float.isNaN(number) && !Float.isInfinite(number)) {
                    result.add(Float.valueOf(number));
                }
            }
        }
        return result;
    }

    private List<Boolean> booleanList(Object value) {
        List<Boolean> result = new ArrayList<Boolean>();
        if (!(value instanceof Iterable<?>)) return result;
        for (Object entry : (Iterable<?>) value) {
            if (result.size() >= 16) break;
            if (entry instanceof Boolean) result.add((Boolean) entry);
        }
        return result;
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (!(value instanceof Iterable<?>)) return result;
        for (Object entry : (Iterable<?>) value) {
            if (result.size() >= 16) break;
            if (entry != null) result.add(String.valueOf(entry));
        }
        return result;
    }

    private List<Integer> colorList(Object value) {
        List<Integer> result = new ArrayList<Integer>();
        if (!(value instanceof Iterable<?>)) return result;
        for (Object entry : (Iterable<?>) value) {
            if (result.size() >= 16) break;
            if (entry instanceof Number) {
                result.add(Integer.valueOf(((Number) entry).intValue()));
                continue;
            }
            Object rgb = invokeNoArg(entry, "asRGB");
            if (!(rgb instanceof Number)) rgb = invokeNoArg(entry, "getRGB");
            if (rgb instanceof Number) result.add(Integer.valueOf(((Number) rgb).intValue()));
        }
        return result;
    }

    private boolean canHaveProfileTexture(String materialName, String registryId) {
        String material = materialName == null ? "" : materialName.toLowerCase(Locale.ROOT);
        String identifier = registryId == null ? "" : registryId.toLowerCase(Locale.ROOT);
        return material.contains("skull") || material.contains("player_head")
                || identifier.endsWith(":player_head") || identifier.endsWith(":player_wall_head")
                || identifier.endsWith(":skull") || identifier.endsWith(":skull_item");
    }

    /**
     * Extracts only the Mojang texture content hash from skull/profile metadata.
     * Full NBT, signatures, UUIDs and owner names are deliberately not persisted.
     */
    private String readProfileTexture(ItemMeta meta) {
        if (meta == null) return "";
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (String method : new String[]{"getPlayerProfile", "getOwnerProfile"}) {
            String hash = textureHashFromProfile(invokeNoArg(meta, method), visited, 0);
            if (!hash.isEmpty()) return hash;
        }

        Object profile = readProfileField(meta);
        return textureHashFromProfile(profile, visited, 0);
    }

    private Object readProfileField(Object target) {
        if (target == null) return null;
        Class<?> current = target.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                String type = field.getType().getSimpleName().toLowerCase(Locale.ROOT);
                if (!(name.equals("profile") || name.equals("ownerprofile")
                        || type.contains("gameprofile") || type.contains("resolvableprofile")
                        || type.equals("playerprofile"))) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                    // Continue through other profile generations.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private String textureHashFromProfile(Object value, Set<Object> visited, int depth) {
        value = unwrapOptional(value);
        if (value == null || depth > 5 || visited.contains(value)) return "";
        visited.add(value);

        if (value instanceof CharSequence) {
            String text = String.valueOf(value);
            String direct = normalizeTextureReference(text);
            if (!direct.isEmpty()) return direct;
            return decodeTextureProperty(text);
        }

        Object textures = invokeNoArg(value, "getTextures");
        if (textures != null) {
            String direct = normalizeTextureReference(String.valueOf(invokeNoArg(textures, "getSkin")));
            if (!direct.isEmpty()) return direct;
        }

        Object properties = invokeNoArg(value, "getProperties");
        String fromProperties = textureHashFromProperties(properties, visited, depth + 1);
        if (!fromProperties.isEmpty()) return fromProperties;

        for (String method : new String[]{"getGameProfile", "gameProfile", "profile", "getProfile"}) {
            Object nested = invokeNoArg(value, method);
            if (nested != null && nested != value) {
                String hash = textureHashFromProfile(nested, visited, depth + 1);
                if (!hash.isEmpty()) return hash;
            }
        }
        return "";
    }

    private String textureHashFromProperties(Object properties, Set<Object> visited, int depth) {
        properties = unwrapOptional(properties);
        if (properties == null || depth > 6) return "";

        Object textureValues = null;
        if (properties instanceof Map<?, ?>) {
            textureValues = ((Map<?, ?>) properties).get("textures");
        }
        if (textureValues == null) {
            textureValues = invokeOneArg(properties, "get", "textures");
        }
        if (textureValues != null && textureValues != properties) {
            String hash = textureHashFromPropertyValue(textureValues, visited, depth + 1);
            if (!hash.isEmpty()) return hash;
        }
        return textureHashFromPropertyValue(properties, visited, depth + 1);
    }

    private String textureHashFromPropertyValue(Object value, Set<Object> visited, int depth) {
        value = unwrapOptional(value);
        if (value == null || depth > 7) return "";
        if (value instanceof Iterable<?>) {
            int inspected = 0;
            for (Object entry : (Iterable<?>) value) {
                if (inspected++ >= 32) break;
                String hash = textureHashFromPropertyValue(entry, visited, depth + 1);
                if (!hash.isEmpty()) return hash;
            }
            return "";
        }
        if (value.getClass().isArray()) {
            int length = Math.min(32, Array.getLength(value));
            for (int i = 0; i < length; i++) {
                String hash = textureHashFromPropertyValue(Array.get(value, i), visited, depth + 1);
                if (!hash.isEmpty()) return hash;
            }
            return "";
        }
        if (value instanceof Map<?, ?>) {
            Object textures = ((Map<?, ?>) value).get("textures");
            if (textures != null) return textureHashFromPropertyValue(textures, visited, depth + 1);
        }

        Object propertyValue = invokeNoArg(value, "getValue");
        if (propertyValue == null) propertyValue = invokeNoArg(value, "value");
        if (propertyValue != null && propertyValue != value) {
            String hash = textureHashFromPropertyValue(propertyValue, visited, depth + 1);
            if (!hash.isEmpty()) return hash;
        }
        return textureHashFromProfile(value, visited, depth + 1);
    }

    private Object unwrapOptional(Object value) {
        if (value == null) return null;
        if (!"java.util.Optional".equals(value.getClass().getName())) return value;
        Object present = invokeNoArg(value, "isPresent");
        return Boolean.TRUE.equals(present) ? invokeNoArg(value, "get") : null;
    }

    private Object invokeOneArg(Object target, String methodName, Object argument) {
        if (target == null) return null;
        Class<?> current = target.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterTypes().length != 1) continue;
                Class<?> type = method.getParameterTypes()[0];
                if (argument != null && !type.isAssignableFrom(argument.getClass()) && type != Object.class) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target, argument);
                } catch (Throwable ignored) {
                    // Try another overload or superclass.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private String decodeTextureProperty(String encoded) {
        if (encoded == null) return "";
        String compact = encoded.replaceAll("\\s+", "");
        if (compact.isEmpty() || compact.length() > MAX_PROFILE_PROPERTY_LENGTH) return "";
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException ignored) {
            try {
                decoded = Base64.getUrlDecoder().decode(compact);
            } catch (IllegalArgumentException ignoredAgain) {
                return "";
            }
        }
        if (decoded.length == 0 || decoded.length > MAX_PROFILE_PROPERTY_LENGTH) return "";
        return normalizeTextureReference(new String(decoded, StandardCharsets.UTF_8));
    }

    private String normalizeTextureReference(String value) {
        if (value == null) return "";
        String clean = value.trim().replace("\\/", "/");
        if (TEXTURE_HASH.matcher(clean).matches()) return clean.toLowerCase(Locale.ROOT);
        Matcher matcher = TEXTURE_URL.matcher(clean);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private boolean booleanMethod(Object target, String methodName) {
        if (target == null) {
            return false;
        }
        try {
            Object result = target.getClass().getMethod(methodName).invoke(target);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ItemStack readOffhand(PlayerInventory inventory) {
        try {
            Method method = inventory.getClass().getMethod("getItemInOffHand");
            Object result = method.invoke(inventory);
            return result instanceof ItemStack ? (ItemStack) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String humanize(String materialName) {
        StringBuilder result = new StringBuilder();
        for (String word : materialName.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.toString();
    }

    private static final class ModelProperties {
        private Integer legacyInteger;
        private String itemModel = "";
        private List<Float> floats = Collections.emptyList();
        private List<Boolean> flags = Collections.emptyList();
        private List<String> strings = Collections.emptyList();
        private List<Integer> colors = Collections.emptyList();
    }

    /** Schedules on the global server thread: primary thread on classic Bukkit, global region thread on Folia. */
    private void runOnPrimaryThread(Runnable runnable) {
        scheduler.executeGlobal(runnable);
    }

    @Override
    public void executeOnPlatformThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        runOnPrimaryThread(runnable);
    }

    /**
     * Runs on the thread that may interact with the sender: the sender's region thread
     * when it is a player on Folia, the global thread otherwise. Command replies must use
     * this instead of {@link #executeOnPlatformThread(Runnable)}, because on Folia a
     * player's messages can only be sent from the region thread that owns the player.
     */
    public void executeOnSenderThread(final CommandSender sender, final Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (sender instanceof Player) {
            scheduler.executeForPlayer((Player) sender, runnable);
        } else {
            scheduler.executeGlobal(runnable);
        }
    }

    @Override
    public void broadcastMessage(final String message) {
        if (scheduler.isFolia()) {
            // On Folia every player must be reached on its own region thread.
            final String text = message == null ? "" : message;
            scheduler.executeGlobal(new Runnable() {
                @Override
                public void run() {
                    Bukkit.getConsoleSender().sendMessage(text);
                }
            });
            for (final Player player : Bukkit.getOnlinePlayers()) {
                scheduler.executeForPlayer(player, new Runnable() {
                    @Override
                    public void run() {
                        if (player.isOnline()) {
                            player.sendMessage(text);
                        }
                    }
                });
            }
            return;
        }
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                Bukkit.broadcastMessage(message == null ? "" : message);
            }
        });
    }

    @Override
    public void disconnectPlayers(final List<String> playerNames, final String reason) {
        if (playerNames == null || playerNames.isEmpty()) {
            return;
        }
        if (scheduler.isFolia()) {
            // Kicks must happen on each player's own region thread.
            final String kickReason = reason == null ? "" : reason;
            for (String playerName : playerNames) {
                if (playerName == null || playerName.trim().isEmpty()) {
                    continue;
                }
                final Player player = Bukkit.getPlayerExact(playerName.trim());
                if (player == null) {
                    continue;
                }
                scheduler.executeForPlayer(player, new Runnable() {
                    @Override
                    public void run() {
                        if (player.isOnline()) {
                            player.kickPlayer(kickReason);
                        }
                    }
                });
            }
            return;
        }
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                for (String playerName : playerNames) {
                    if (playerName == null || playerName.trim().isEmpty()) {
                        continue;
                    }
                    Player player = Bukkit.getPlayerExact(playerName.trim());
                    if (player != null && player.isOnline()) {
                        player.kickPlayer(reason == null ? "" : reason);
                    }
                }
            }
        });
    }

    @Override
    public void broadcastRichMessage(final List<ChatPart> parts) {
        if (scheduler.isFolia()) {
            // Each player must receive the chat component on its own region thread.
            for (final Player player : Bukkit.getOnlinePlayers()) {
                scheduler.executeForPlayer(player, new Runnable() {
                    @Override
                    public void run() {
                        if (player.isOnline()) {
                            player.spigot().sendMessage(buildRichComponents(parts));
                        }
                    }
                });
            }
            return;
        }
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                BaseComponent[] output = buildRichComponents(parts);
                Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
                for (Player player : onlinePlayers) {
                    player.spigot().sendMessage(output);
                }
            }
        });
    }

    private BaseComponent[] buildRichComponents(List<ChatPart> parts) {
        List<BaseComponent> components = new ArrayList<BaseComponent>();
        if (parts != null) {
            for (ChatPart part : parts) {
                if (part == null || part.getText().isEmpty()) {
                    continue;
                }
                BaseComponent[] parsed = TextComponent.fromLegacyText(part.getText());
                for (BaseComponent component : parsed) {
                    if (part.hasClickUrl()) {
                        component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, part.getClickUrl()));
                        String hover = part.getHoverText().isEmpty() ? "点击打开" : part.getHoverText();
                        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                TextComponent.fromLegacyText("§7" + hover)));
                    }
                    components.add(component);
                }
            }
        }
        return components.toArray(new BaseComponent[components.size()]);
    }

    @Override
    public void info(String message) {
        plugin.getLogger().info(message);
    }

    @Override
    public void warn(String message) {
        plugin.getLogger().warning(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, message, throwable);
    }
}
