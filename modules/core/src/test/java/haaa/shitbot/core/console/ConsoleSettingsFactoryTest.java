package haaa.shitbot.core.console;

import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.support.MapConfigSource;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class ConsoleSettingsFactoryTest {
    @Test
    public void defaultsKeepBackendListenerDisabledAndShortcutsEmpty() {
        ConsoleSettings settings = create(new MapConfigSource(), new MapConfigSource());
        assertFalse(settings.getBackendTransport().getListener().isEnabled());
        assertTrue(settings.getShortcuts().isEmpty());
        assertTrue(settings.getImageTemplateCommands().isEmpty());
        assertEquals(15, settings.getRequestTimeoutSeconds());
        assertEquals(Collections.singletonList("TPS"), settings.getTps().getAliases());
    }

    @Test
    public void aliasesAreUniqueAcrossTpsShortcutsAndTemplatesIgnoringCase() {
        MapConfigSource config = new MapConfigSource()
                .put("tps.aliases", Arrays.asList("TPS", "tps"))
                .put("shortcuts.list.aliases", Arrays.asList("tPs", " List ", "list"))
                .put("shortcuts.list.command", " list ")
                .put("image-templates.commands.online.aliases", Arrays.asList("LIST", " Online "))
                .put("image-templates.commands.online.template", "online-status");
        ConsoleSettings settings = create(config, new MapConfigSource());
        assertEquals(Collections.singletonList("TPS"), settings.getTps().getAliases());
        assertEquals(Collections.singletonList("List"), settings.getShortcuts().get(0).getAliases());
        assertEquals("list", settings.getShortcuts().get(0).getCommand());
        assertEquals(Collections.singletonList("Online"), settings.getImageTemplateCommands().get(0).getAliases());
    }

    @Test
    public void translatedAliasesAndMessagesOverrideLegacyConfig() {
        MapConfigSource config = new MapConfigSource().put("tps.aliases", Collections.singletonList("legacy"))
                .put("messages.no-permission", "legacy denial");
        MapConfigSource language = new MapConfigSource().put("console.tps.aliases", Collections.singletonList("status"))
                .put("console.messages.no-permission", "permission denied");
        ConsoleSettings settings = create(config, language);
        assertEquals(Collections.singletonList("status"), settings.getTps().getAliases());
        assertEquals("permission denied", settings.getNoPermissionMessage());
    }

    @Test
    public void invalidCommandsAndTemplateIdsAreNotRegistered() {
        MapConfigSource config = new MapConfigSource()
                .put("shortcuts.blank.aliases", Collections.singletonList("empty"))
                .put("shortcuts.blank.command", " ")
                .put("shortcuts.no-alias.command", "list")
                .put("image-templates.commands.bad.aliases", Collections.singletonList("bad"))
                .put("image-templates.commands.bad.template", "../outside");
        ConsoleSettings settings = create(config, new MapConfigSource());
        assertTrue(settings.getShortcuts().isEmpty());
        assertTrue(settings.getImageTemplateCommands().isEmpty());
    }

    @Test
    public void durationSuffixAndOutOfRangeTimeoutsUseDocumentedDefaults() {
        MapConfigSource config = new MapConfigSource().put("request-timeout-seconds", 61)
                .put("command-cooldown-seconds", -1)
                .put("shortcuts.valid.aliases", Collections.singletonList("valid"))
                .put("shortcuts.valid.command", "list")
                .put("shortcuts.valid.capture-seconds", " 12 S ")
                .put("shortcuts.invalid.aliases", Collections.singletonList("invalid"))
                .put("shortcuts.invalid.command", "list")
                .put("shortcuts.invalid.capture-seconds", "not-a-duration")
                .put("shortcuts.range.aliases", Collections.singletonList("range"))
                .put("shortcuts.range.command", "list")
                .put("shortcuts.range.capture-seconds", "31s");
        ConsoleSettings settings = create(config, new MapConfigSource());
        assertEquals(15, settings.getRequestTimeoutSeconds());
        assertEquals(5, settings.getCommandCooldownSeconds());
        assertEquals(12, settings.getShortcuts().get(0).getCaptureSeconds());
        assertEquals(5, settings.getShortcuts().get(1).getCaptureSeconds());
        assertEquals(5, settings.getShortcuts().get(2).getCaptureSeconds());
    }

    private static ConsoleSettings create(MapConfigSource config, MapConfigSource language) {
        return ConsoleSettingsFactory.create(config, new Translations("zh_CN", language, new MapConfigSource()));
    }
}
