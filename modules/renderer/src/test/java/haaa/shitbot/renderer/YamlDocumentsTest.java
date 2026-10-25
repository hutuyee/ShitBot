package haaa.shitbot.renderer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class YamlDocumentsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final YamlDocuments yaml = new YamlDocuments();

    @Test
    public void mappingRoundTripPreservesNestedUnicodeAndNumericValues() throws Exception {
        Map<String, Object> document = yaml.loadMap("name: 玩家\ncanvas: {width: 32, height: 16}\nlayers: [{type: rectangle}]\n");
        assertEquals("玩家", document.get("name"));
        assertEquals(32, ((Map<?, ?>) document.get("canvas")).get("width"));
        assertEquals(document, yaml.loadMap(yaml.dump(document)));
    }

    @Test
    public void duplicateKeysAreRejectedAtEveryLevel() {
        assertThrows(IOException.class, () -> yaml.loadMap("width: 1\nwidth: 2\n"));
        assertThrows(IOException.class, () -> yaml.loadMap("canvas:\n  width: 1\n  width: 2\n"));
    }

    @Test
    public void rejectsEmptyScalarSequenceAndJavaObjectDocuments() {
        for (String source : new String[] {null, "", "# comment", "scalar", "[a, b]", "!!java.net.URL ['https://example.invalid']"}) {
            assertThrows(IOException.class, () -> yaml.loadMap(source));
        }
    }

    @Test
    public void enforcesUtf8ByteLimitsAndAliasLimits() {
        String oversized = "value: " + String.join("", Collections.nCopies(180000, "中"));
        assertThrows(IOException.class, () -> yaml.loadMap(oversized));
        String aliases = "base: &base [one]\nvalues: [" + String.join(",", Collections.nCopies(21, "*base")) + "]\n";
        assertThrows(IOException.class, () -> yaml.loadMap(aliases));
    }

    @Test
    public void onlyExplicitlyAllowedEmptyFilesBecomeEmptyMappings() throws Exception {
        Path file = temporary.newFile("empty.yml").toPath();
        assertThrows(IOException.class, () -> yaml.loadMap(file));
        assertTrue(yaml.loadMap(file, true).isEmpty());
        Files.write(file, "# intentionally empty\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(yaml.loadMap(file, true).isEmpty());
    }

    @Test
    public void fileLoadingRejectsMissingDirectoriesAndOversizedFiles() throws Exception {
        Path root = temporary.getRoot().toPath();
        assertThrows(IOException.class, () -> yaml.loadMap(root.resolve("missing.yml")));
        assertThrows(IOException.class, () -> yaml.loadMap(root));
        Path large = temporary.newFile("large.yml").toPath();
        byte[] bytes = new byte[512 * 1024 + 1];
        Arrays.fill(bytes, (byte) ' ');
        Files.write(large, bytes);
        assertThrows(IOException.class, () -> yaml.loadMap(large, true));
    }
}
