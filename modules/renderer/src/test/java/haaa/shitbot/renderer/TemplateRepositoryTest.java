package haaa.shitbot.renderer;

import haaa.shitbot.api.spi.ImageTemplateEngineSettings;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static haaa.shitbot.renderer.RendererTestSupport.*;
import static org.junit.Assert.*;

public class TemplateRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private ImageTemplateEngineSettings settings;
    private TemplateRepository repository;

    @Before
    public void createDraft() throws Exception {
        root = temporary.newFolder("templates").toPath();
        settings = settings(root);
        repository = new TemplateRepository(settings, HOST);
        repository.createDraft("test-card", "First");
        repository.saveDraftSources("test-card", manifest("First"), scene(32));
    }

    @Test
    public void draftsBecomeDiscoverableOnlyAfterPublishing() throws Exception {
        assertFalse(repository.findInfo("test-card").isPresent());
        assertThrows(IOException.class, () -> repository.loadPublished("test-card"));
        assertEquals(1L, repository.publish("test-card"));
        assertTrue(repository.findInfo("test-card").isPresent());
        assertEquals("First", repository.loadPublished("test-card").getInfo().getName());
    }

    @Test
    public void publishingSnapshotsDraftsAndRollbackRestoresThePreviousVersion() throws Exception {
        repository.publish("test-card");
        repository.saveDraftSources("test-card", manifest("Second"), scene(48));
        assertEquals(32, repository.loadPublished("test-card").getInfo().getWidth());
        assertEquals(2L, repository.publish("test-card"));
        assertEquals(Arrays.asList(2L, 1L), repository.listVersions("test-card"));
        assertEquals(48, repository.loadPublished("test-card").getInfo().getWidth());
        repository.rollback("test-card", 1);
        assertEquals("First", repository.loadPublished("test-card").getInfo().getName());
        assertEquals(1L, repository.findInfo("test-card").get().getVersion());
        assertEquals(48, repository.loadDraft("test-card").getInfo().getWidth());
        assertEquals(3L, repository.publish("test-card"));
    }

    @Test
    public void invalidSaveLeavesBothExistingDraftDocumentsIntact() throws Exception {
        Map<String, String> before = repository.readDraftSources("test-card");
        assertThrows(IOException.class, () -> repository.saveDraftSources("test-card", manifest("Broken"),
                "schema-version: scene-v1\ncanvas: {width: 32, height: 16}\nlayers: [{type: unknown}]\n"));
        assertEquals(before, repository.readDraftSources("test-card"));
    }

    @Test
    public void rejectsModifiedPublishedSnapshotsWhenLoadingFromDisk() throws Exception {
        repository.publish("test-card");
        Files.write(root.resolve("test-card/versions/1/scene.yml"), scene(48).getBytes(StandardCharsets.UTF_8));
        TemplateRepository fresh = new TemplateRepository(settings, HOST);
        IOException error = assertThrows(IOException.class, () -> fresh.loadPublished("test-card"));
        assertTrue(error.getMessage().contains("snapshot was modified"));
    }

    @Test
    public void templateIdsAndAssetPathsCannotEscapeTheirDirectories() throws Exception {
        for (String id : new String[] {"../outside", "a/b", "a\\b", "Uppercase", ""}) {
            assertThrows(IOException.class, () -> repository.createDraft(id, "invalid"));
        }
        for (String path : new String[] {"../outside.png", "nested/../../outside.png", "..\\outside.png", "/outside.png", "C:\\outside.png"}) {
            assertThrows(IOException.class, () -> repository.uploadAsset("test-card", path, new byte[] {1}));
        }
        repository.uploadAsset("test-card", "nested/icon.bin", new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(root.resolve("test-card/assets/nested/icon.bin")));
        assertFalse(Files.exists(root.resolve("outside.png")));
    }

    @Test
    public void assetBudgetsAccountForReplacementInsteadOfDoubleCounting() throws Exception {
        byte[] maximum = new byte[4096];
        repository.uploadAsset("test-card", "first.bin", maximum);
        repository.uploadAsset("test-card", "second.bin", maximum);
        maximum[0] = 42;
        repository.uploadAsset("test-card", "first.bin", maximum);
        assertEquals(42, Files.readAllBytes(root.resolve("test-card/assets/first.bin"))[0]);
        assertThrows(IOException.class, () -> repository.uploadAsset("test-card", "third.bin", new byte[] {1}));
        assertThrows(IOException.class, () -> repository.uploadAsset("test-card", "first.bin", new byte[4097]));
        assertEquals(4096L, Files.size(root.resolve("test-card/assets/first.bin")));
    }

    @Test
    public void rejectsInvalidCanvasAndProviderDeclarations() {
        for (int width : new int[] {0, 2049}) {
            assertThrows(IOException.class, () -> repository.saveDraftSources("test-card", manifest("Bad"), scene(width)));
        }
        assertThrows(IOException.class, () -> repository.saveDraftSources("test-card", manifest("Bad"),
                "schema-version: scene-v1\ncanvas: {width: 2048, height: 2048}\nlayers: []\n"));
        String duplicateProviders = manifest("Bad").replace("providers: []", "providers: [player, player]");
        assertThrows(IOException.class, () -> repository.saveDraftSources("test-card", duplicateProviders, scene(32)));
        String wrongId = manifest("Bad").replace("id: test-card", "id: another-card");
        assertThrows(IOException.class, () -> repository.saveDraftSources("test-card", wrongId, scene(32)));
    }

    @Test
    public void initializationInstallsDefaultsAndRecoversAMissingPublishedPointer() throws Exception {
        repository.initialize();
        for (String id : Arrays.asList("online-status", "player-profile", "inventory")) {
            assertTrue(id, repository.findInfo(id).isPresent());
            assertEquals(1L, repository.loadPublished(id).getInfo().getVersion());
        }
        Files.delete(root.resolve("online-status/published.yml"));
        Map<String, String> draft = repository.readDraftSources("online-status");
        repository.saveDraftSources("online-status", draft.get("manifest"), scene(32));
        repository.initialize();
        assertEquals(1L, repository.loadPublished("online-status").getInfo().getVersion());
        assertEquals(Arrays.asList(1L), repository.listVersions("online-status"));
        assertNotEquals(32, repository.loadPublished("online-status").getInfo().getWidth());
    }
}
