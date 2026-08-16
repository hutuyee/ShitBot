package haaa.shitbotspigot.platform;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the reflection-based Folia scheduler lookup against API shape drift.
 * Folia has used different RegionScheduler/EntityScheduler signatures across
 * versions (entity overloads were removed entirely), so the finder must match
 * leniently by parameter compatibility instead of exact types.
 */
public final class AdapterLogicTest {

    /** Mirrors the new-Folia RegionScheduler: entity scheduling removed, location-only. */
    interface NewRegionSchedulerSim {
        Object execute(Plugin plugin, Location location, Runnable run);
        Object execute(Plugin plugin, World world, int chunkX, int chunkZ, Runnable run);
    }

    /** Mirrors the old-Folia RegionScheduler: entity overload present. */
    interface OldRegionSchedulerSim {
        Object execute(Plugin plugin, Entity entity, Runnable run);
        Object execute(Plugin plugin, Location location, Runnable run);
    }

    /** Mirrors EntityScheduler as documented by Paper 26.1.2. */
    interface EntitySchedulerSim {
        boolean execute(Plugin plugin, Runnable run, Runnable retired, long delay);
    }

    /** Mirrors GlobalRegionScheduler. */
    interface GlobalSchedulerSim {
        Object execute(Plugin plugin, Runnable run);
    }

    private static Method finder() throws Exception {
        Method finder = SchedulerAdapter.class.getDeclaredMethod("executeMethod", Class.class, int.class);
        finder.setAccessible(true);
        return finder;
    }

    @Test
    public void newFoliaRegionSchedulerHasNoEntityExecute() throws Exception {
        assertNull(finder().invoke(null, NewRegionSchedulerSim.class, Integer.valueOf(3)));
    }

    @Test
    public void oldFoliaRegionSchedulerEntityExecuteMatches() throws Exception {
        Method matched = (Method) finder().invoke(null, OldRegionSchedulerSim.class, Integer.valueOf(3));
        assertNotNull(matched);
        assertEquals(Entity.class, matched.getParameterTypes()[1]);
    }

    @Test
    public void entitySchedulerExecuteMatches() throws Exception {
        Method matched = (Method) finder().invoke(null, EntitySchedulerSim.class, Integer.valueOf(4));
        assertNotNull(matched);
        Class<?>[] params = matched.getParameterTypes();
        assertTrue(params[1].isAssignableFrom(Runnable.class));
        assertTrue(params[2].isAssignableFrom(Runnable.class));
        assertEquals(long.class, params[3]);
    }

    @Test
    public void globalSchedulerExecuteMatches() throws Exception {
        assertNotNull(finder().invoke(null, GlobalSchedulerSim.class, Integer.valueOf(2)));
    }

    @Test
    public void noCrossParamCountMatches() throws Exception {
        assertNull(finder().invoke(null, GlobalSchedulerSim.class, Integer.valueOf(4)));
    }

    @Test
    public void detectFoliaIsFalseOnPlainClasspath() throws Exception {
        Method detect = SchedulerAdapter.class.getDeclaredMethod("detectFolia");
        detect.setAccessible(true);
        assertEquals(Boolean.FALSE, detect.invoke(null));
    }
}
