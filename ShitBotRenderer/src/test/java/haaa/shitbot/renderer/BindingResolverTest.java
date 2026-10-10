package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageRenderRequest;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class BindingResolverTest {
    @Test
    public void exactBindingsPreserveTypesAndResolveMapsListsAndArrays() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("players", Arrays.asList(Collections.singletonMap("name", "Steve")));
        data.put("scores", new int[] {10, 20});
        data.put("online", true);
        BindingResolver resolver = new BindingResolver(new ImageRenderRequest(null, data));
        assertEquals("Steve", resolver.value("${data.players.0.name}"));
        assertEquals(Integer.valueOf(20), resolver.value(" ${data.scores.1} "));
        assertEquals(Boolean.TRUE, resolver.value("${data.online}"));
        assertSame(data.get("players"), resolver.value("${data.players}"));
    }

    @Test
    public void embeddedBindingsEscapeReplacementCharactersAndOmitMissingValues() {
        BindingResolver resolver = new BindingResolver(ImageRenderRequest.of(Collections.singletonMap("name", "$Steve\\Alex")));
        assertEquals("Hello $Steve\\Alex! ", resolver.value("Hello ${context.name}! ${data.missing}"));
        assertNull(resolver.value("${data.missing}"));
        assertEquals("fallback", resolver.text("${data.missing}", "fallback"));
    }

    @Test
    public void invalidIndicesAndScalarPropertyAccessResolveToNull() {
        BindingResolver resolver = new BindingResolver(ImageRenderRequest.of(
                Collections.singletonMap("players", Arrays.asList("Steve")))).with("scores", new int[] {5});
        for (String path : new String[] {"context.players.-1", "context.players.1", "context.players.bad",
                "context.players.0.class", "scores.-1", "scores.1", "scores.bad"}) {
            assertNull(path, resolver.value("${" + path + "}"));
        }
    }

    @Test
    public void nestedLoopBindingsDoNotLeakIntoTheirParent() {
        BindingResolver root = new BindingResolver((ImageRenderRequest) null).with("item", "parent");
        BindingResolver child = root.with("item", "child").with("index", 2);
        assertEquals("parent", root.value("${item}"));
        assertNull(root.value("${index}"));
        assertEquals("child", child.value("${item}"));
        assertEquals(2, child.value("${index}"));
    }

    @Test
    public void parsesBooleanAndNumericBindingsWithFallbacks() {
        BindingResolver resolver = new BindingResolver((ImageRenderRequest) null);
        assertEquals(12.5D, resolver.number(" 12.5 ", -1), 0.0D);
        assertEquals(-1.0D, resolver.number("missing", -1), 0.0D);
        assertTrue(resolver.bool("YES", false));
        assertFalse(resolver.bool("off", true));
        assertTrue(resolver.bool(2, false));
        assertFalse(resolver.bool(0, true));
        assertTrue(resolver.bool("unknown", true));
        assertFalse(resolver.bool("unknown", false));
    }

    @Test
    public void conditionTruthinessHandlesEmptyCollectionsAndArrays() {
        BindingResolver resolver = new BindingResolver((ImageRenderRequest) null);
        for (Object value : new Object[] {null, false, 0, "", "false", "0", Collections.emptyList(),
                Collections.emptyMap(), new int[0]}) {
            assertFalse(resolver.truthy(value));
        }
        for (Object value : new Object[] {true, -1, "Steve", Collections.singletonList("Steve"),
                Collections.singletonMap("name", "Steve"), new int[] {0}}) {
            assertTrue(resolver.truthy(value));
        }
    }
}
