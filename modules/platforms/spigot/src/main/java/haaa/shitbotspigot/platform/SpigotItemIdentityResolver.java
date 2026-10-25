package haaa.shitbotspigot.platform;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reflection-only registry lookup that keeps the Spigot module linkable from 1.8 onward. */
final class SpigotItemIdentityResolver {
    private static final Pattern IDENTIFIER = Pattern.compile("([a-z0-9_.-]+):([a-z0-9_./-]+)");
    private static final Object UNAVAILABLE = new Object();
    private static final Method UNAVAILABLE_METHOD = unavailableMethodMarker();

    private final boolean moddedRuntime;
    private final ConcurrentHashMap<Class<?>, Method> itemGetters =
            new ConcurrentHashMap<Class<?>, Method>();
    private volatile Object craftCopyMethod = null;
    private volatile RegistryAccessor registryAccessor;
    private volatile boolean registryAccessorInitialized;
    private volatile long registryRetryAfter;

    SpigotItemIdentityResolver() {
        this.moddedRuntime = classExists("net.minecraftforge.registries.ForgeRegistries")
                || classExists("net.minecraftforge.fml.common.registry.ForgeRegistries")
                || classExists("net.neoforged.neoforge.registries.NeoForgeRegistries")
                || classExists("net.minecraftforge.fml.common.Loader")
                || classExists("net.fabricmc.loader.api.FabricLoader")
                || classExists("org.cardboardpowered.CardboardConfig");
    }

    String resolve(ItemStack stack) {
        if (stack == null || stack.getType() == null) return "minecraft:air";
        String bukkit = bukkitKey(stack.getType());
        if (bukkit != null && (!"minecraft".equals(namespace(bukkit)) || !moddedRuntime)) {
            return bukkit;
        }
        if (moddedRuntime) {
            String nms = nmsKey(stack);
            if (nms != null) return nms;
        }
        if (bukkit != null) return bukkit;
        String name = stack.getType().name().toLowerCase(Locale.ROOT);
        if (name.startsWith("legacy_")) name = name.substring("legacy_".length());
        return "minecraft:" + name;
    }

    private String bukkitKey(Material material) {
        try {
            Method getKey = material.getClass().getMethod("getKey");
            return normalizeIdentifier(getKey.invoke(material));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String nmsKey(ItemStack stack) {
        try {
            Method copy = craftCopyMethod();
            if (copy == null) return null;
            Object nmsStack = copy.invoke(null, stack);
            if (nmsStack == null) return null;
            Method getter = findItemGetter(nmsStack.getClass());
            if (getter == null) return null;
            Object item = getter.invoke(nmsStack);
            if (item == null) return null;
            RegistryAccessor accessor = registryAccessor(item.getClass());
            return accessor == null ? null : normalizeIdentifier(accessor.lookup(item));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Method craftCopyMethod() {
        Object cached = craftCopyMethod;
        if (cached == UNAVAILABLE) return null;
        if (cached instanceof Method) return (Method) cached;
        synchronized (this) {
            cached = craftCopyMethod;
            if (cached == UNAVAILABLE) return null;
            if (cached instanceof Method) return (Method) cached;
            try {
                String packageName = Bukkit.getServer().getClass().getPackage().getName();
                Class<?> craft = Class.forName(packageName + ".inventory.CraftItemStack");
                Method method = craft.getMethod("asNMSCopy", ItemStack.class);
                method.setAccessible(true);
                craftCopyMethod = method;
                return method;
            } catch (Throwable ignored) {
                craftCopyMethod = UNAVAILABLE;
                return null;
            }
        }
    }

    private Method findItemGetter(Class<?> stackClass) {
        Method cached = itemGetters.get(stackClass);
        if (cached != null) return cached == UNAVAILABLE_METHOD ? null : cached;
        Method found = namedNoArgMethod(stackClass, "getItem", "func_77973_b");
        if (found == null) {
            for (Method method : stackClass.getMethods()) {
                if (method.getParameterTypes().length == 0
                        && method.getReturnType().getSimpleName().equalsIgnoreCase("Item")) {
                    found = method;
                    break;
                }
            }
        }
        if (found != null) found.setAccessible(true);
        itemGetters.put(stackClass, found == null ? UNAVAILABLE_METHOD : found);
        return found;
    }

    private static Method unavailableMethodMarker() {
        try {
            return SpigotItemIdentityResolver.class.getDeclaredMethod("unavailableMarker");
        } catch (NoSuchMethodException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    @SuppressWarnings("unused")
    private static void unavailableMarker() {
    }

    private RegistryAccessor registryAccessor(Class<?> itemClass) {
        if (registryAccessorInitialized) return registryAccessor;
        long now = System.currentTimeMillis();
        if (now < registryRetryAfter) return null;
        synchronized (this) {
            if (registryAccessorInitialized) return registryAccessor;
            now = System.currentTimeMillis();
            if (now < registryRetryAfter) return null;
            registryAccessor = findKnownRegistry(itemClass);
            if (registryAccessor == null) registryAccessor = findLegacyItemRegistry(itemClass);
            // Only cache a successful lookup. Hybrid servers can initialize registry
            // classes late, while a timed negative cache avoids repeating reflection
            // for every slot when a particular implementation is unsupported.
            registryAccessorInitialized = registryAccessor != null;
            registryRetryAfter = registryAccessorInitialized ? Long.MAX_VALUE : now + 30000L;
            return registryAccessor;
        }
    }

    private RegistryAccessor findKnownRegistry(Class<?> itemClass) {
        String[][] classesAndFields = {
                {"net.minecraftforge.registries.ForgeRegistries", "ITEMS"},
                {"net.minecraftforge.fml.common.registry.ForgeRegistries", "ITEMS"},
                {"net.minecraft.core.registries.BuiltInRegistries", "ITEM"},
                {"net.minecraft.core.IRegistry", "ITEM"}
        };
        for (String[] pair : classesAndFields) {
            try {
                Class<?> owner = Class.forName(pair[0]);
                Field field = findField(owner, pair[1]);
                if (field == null) continue;
                Object registry = field.get(null);
                Method lookup = findRegistryLookup(registry.getClass(), itemClass);
                if (lookup != null) return new RegistryAccessor(registry, lookup);
            } catch (Throwable ignored) {
                // Try the next registry generation.
            }
        }
        return null;
    }

    private RegistryAccessor findLegacyItemRegistry(Class<?> itemClass) {
        Class<?> current = itemClass;
        while (current != null) {
            for (String name : new String[]{"REGISTRY", "itemRegistry", "field_150901_e"}) {
                try {
                    Field field = current.getDeclaredField(name);
                    if (!Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object registry = field.get(null);
                    if (registry == null) continue;
                    Method lookup = findRegistryLookup(registry.getClass(), itemClass);
                    if (lookup != null) return new RegistryAccessor(registry, lookup);
                } catch (Throwable ignored) {
                    // Keep looking through legacy field names and superclasses.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Method findRegistryLookup(Class<?> registryClass, Class<?> itemClass) {
        for (String name : new String[]{"getKey", "getNameForObject", "func_148750_c", "c", "b"}) {
            Method method = namedOneArgMethod(registryClass, name, itemClass);
            if (method != null && method.getReturnType() != Void.TYPE) return method;
        }
        for (Method method : registryClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isAssignableFrom(itemClass)
                    && method.getReturnType() != Void.TYPE && method.getReturnType() != Integer.TYPE) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private Method namedNoArgMethod(Class<?> type, String... names) {
        for (String name : names) {
            Class<?> current = type;
            while (current != null) {
                try {
                    Method method = current.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method;
                } catch (Throwable ignored) {
                    current = current.getSuperclass();
                }
            }
        }
        return null;
    }

    private Method namedOneArgMethod(Class<?> type, String name, Class<?> argumentClass) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals(name) && parameters.length == 1
                        && parameters[0].isAssignableFrom(argumentClass)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                if (!Modifier.isStatic(field.getModifiers())) return null;
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private String normalizeIdentifier(Object value) {
        if (value == null) return null;
        Matcher matcher = IDENTIFIER.matcher(String.valueOf(value).toLowerCase(Locale.ROOT));
        String selected = null;
        while (matcher.find()) selected = matcher.group(1) + ":" + matcher.group(2);
        return selected;
    }

    private String namespace(String identifier) {
        int colon = identifier.indexOf(':');
        return colon < 0 ? "minecraft" : identifier.substring(0, colon);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, SpigotItemIdentityResolver.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class RegistryAccessor {
        private final Object registry;
        private final Method lookup;

        private RegistryAccessor(Object registry, Method lookup) {
            this.registry = registry;
            this.lookup = lookup;
        }

        private Object lookup(Object item) throws Exception {
            return lookup.invoke(registry, item);
        }
    }
}
