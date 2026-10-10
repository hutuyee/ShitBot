package haaa.shitbot.api;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ImageApiContractTest {
    @Test
    public void renderRequestCopiesMapsAndIgnoresBlankKeys() {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("player", "Steve");
        context.put(null, "invalid");
        context.put("  ", "invalid");
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("online", 3);

        ImageRenderRequest request = new ImageRenderRequest(context, data);
        context.put("player", "Alex");
        data.clear();

        assertEquals(Collections.singletonMap("player", "Steve"), request.getContext());
        assertEquals(Collections.singletonMap("online", 3), request.getData());
        assertThrows(UnsupportedOperationException.class, () -> request.getContext().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> request.getData().clear());
    }

    @Test
    public void resolvedDataOverridesExistingValuesWithoutChangingOriginalRequest() {
        ImageRenderRequest original = new ImageRenderRequest(
                Collections.singletonMap("player", "Steve"), Collections.singletonMap("online", 1));
        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        resolved.put("online", 2);
        resolved.put("tps", 20);
        resolved.put(null, "invalid");
        resolved.put(" ", "invalid");

        ImageRenderRequest updated = original.withData(resolved);
        resolved.clear();

        assertEquals(Collections.singletonMap("online", 1), original.getData());
        assertEquals(2, updated.getData().get("online"));
        assertEquals(20, updated.getData().get("tps"));
        assertEquals(2, updated.getData().size());
        assertEquals(original.getContext(), updated.getContext());
    }

    @Test
    public void nullRenderInputsAndResolvedDataAreSupported() {
        ImageRenderRequest empty = ImageRenderRequest.of(null);
        assertTrue(empty.getContext().isEmpty());
        assertTrue(empty.getData().isEmpty());
        ImageRenderRequest request = new ImageRenderRequest(null, Collections.singletonMap("online", 1));
        assertEquals(request.getData(), request.withData(null).getData());
    }

    @Test
    public void providerIdsAcceptTheDocumentedBoundary() {
        assertEquals("player-profile", new ImageDataProviderSpec(" player-profile ", null).getId());
        String maximum = String.join("", Collections.nCopies(64, "a"));
        assertEquals(maximum, new ImageDataProviderSpec(maximum, null).getId());
        assertThrows(IllegalArgumentException.class, () -> new ImageDataProviderSpec(maximum + "a", null));
    }

    @Test
    public void providerIdsRejectPathsAndInvalidNames() {
        for (String id : new String[] {null, "", " ", "../player", "a/b", "a\\b", "Player", "-player", "a.b"}) {
            assertThrows("id=" + id, IllegalArgumentException.class, () -> new ImageDataProviderSpec(id, null));
        }
    }

    @Test
    public void providerOptionsAreCopiedAndReadOnly() {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("timeout", 5);
        options.put(null, "invalid");
        ImageDataProviderSpec spec = new ImageDataProviderSpec("player", options);
        options.clear();
        assertEquals(Collections.singletonMap("timeout", 5), spec.getOptions());
        assertThrows(UnsupportedOperationException.class, () -> spec.getOptions().put("timeout", 10));
    }

    @Test
    public void renderedBytesCannotBeMutatedThroughTheApi() {
        byte[] bytes = {1, 2, 3};
        ImageRenderResult result = new ImageRenderResult(bytes, 16, 16, null, null, "profile", 1);
        bytes[0] = 9;
        result.getBytes()[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, result.getBytes());
    }

    @Test
    public void emptyRenderedOutputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ImageRenderResult(null, 1, 1, null, null, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageRenderResult(new byte[0], 1, 1, null, null, null, 1));
    }
}
