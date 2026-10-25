package haaa.shitbot.core.console;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PlaceholderResultCodecTest {
    @Test
    public void roundTripPreservesUnicodeNewlinesAndSeparators() throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("%player_name%", "玩家😀\nname=value");
        values.put("%empty%", "");
        assertEquals(values, PlaceholderResultCodec.decode(PlaceholderResultCodec.encode(values)));
    }

    @Test
    public void decodesAnExistingUnpaddedUrlBase64Payload() throws Exception {
        assertEquals(Collections.singletonMap("key", "value"), PlaceholderResultCodec.decode("a2V5=dmFsdWU"));
    }

    @Test
    public void nullValuesBecomeEmptyAndNullKeysAreSkipped() throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(null, "ignored");
        values.put("key", null);
        assertEquals(Collections.singletonMap("key", ""),
                PlaceholderResultCodec.decode(PlaceholderResultCodec.encode(values)));
        assertEquals("", PlaceholderResultCodec.encode(null));
        assertTrue(PlaceholderResultCodec.decode(null).isEmpty());
        assertTrue(PlaceholderResultCodec.decode("").isEmpty());
    }

    @Test
    public void valuesAreTruncatedAtTheProtocolLimit() throws Exception {
        String text = String.join("", Collections.nCopies(513, "x"));
        String encoded = PlaceholderResultCodec.encode(Collections.singletonMap("key", text));
        assertEquals(text.substring(0, 512), PlaceholderResultCodec.decode(encoded).get("key"));
    }

    @Test
    public void rejectsMalformedBase64EmptyKeysAndTrailingLines() {
        for (String encoded : new String[] {"missing-separator", "=dmFsdWU", "***=dmFsdWU", "a2V5=***", "a2V5=\n"}) {
            assertThrows("payload=" + encoded, IOException.class, () -> PlaceholderResultCodec.decode(encoded));
        }
    }

    @Test
    public void rejectsDuplicateKeysEvenWhenTheFirstValueIsEmpty() {
        assertThrows(IOException.class, () -> PlaceholderResultCodec.decode("a2V5=\na2V5=dmFsdWU"));
    }

    @Test
    public void enforcesTotalSizeOnEncodingAndDecoding() {
        String large = String.join("", Collections.nCopies(12001, "x"));
        assertThrows(IOException.class, () -> PlaceholderResultCodec.decode(large));
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int index = 0; index < 20; index++) values.put("key" + index, large);
        assertThrows(IOException.class, () -> PlaceholderResultCodec.encode(values));
    }
}
