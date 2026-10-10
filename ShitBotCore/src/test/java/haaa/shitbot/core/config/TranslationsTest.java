package haaa.shitbot.core.config;

import haaa.shitbot.core.support.MapConfigSource;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class TranslationsTest {
    @Test
    public void selectsLanguageThenFallbackThenCallerDefault() {
        Translations text = new Translations("en_US", new MapConfigSource().put("selected", "English"),
                new MapConfigSource().put("selected", "中文").put("fallback", "后备"));
        assertEquals("English", text.get("selected"));
        assertEquals("后备", text.get("fallback"));
        assertEquals("default", text.get("missing", "default"));
        assertEquals("missing", text.get("missing"));
        assertEquals("English default", text.get("missing", "中文默认值", "English default"));
    }

    @Test
    public void explicitEmptyTranslationDoesNotFallBack() {
        Translations text = new Translations("zh_CN", new MapConfigSource().put("message", ""),
                new MapConfigSource().put("message", "fallback"));
        assertEquals("", text.get("message"));
    }

    @Test
    public void cleansListsAndFallsBackWhenNoUsableAliasesRemain() {
        Translations text = new Translations("en_US",
                new MapConfigSource().put("aliases", Arrays.asList(null, " ")),
                new MapConfigSource().put("aliases", Arrays.asList(" TPS ", "", null, "status")));
        List<String> aliases = text.getList("aliases");
        assertEquals(Arrays.asList("TPS", "status"), aliases);
        assertThrows(UnsupportedOperationException.class, () -> aliases.add("extra"));
        assertEquals(Collections.singletonList("fallback"), text.getList("missing", Arrays.asList(" fallback ", "")));
    }

    @Test
    public void formatsLiteralTokensWithoutInterpretingReplacementMetacharacters() {
        Translations text = new Translations("en_US", new MapConfigSource().put("greeting", "%player%: %count%"),
                new MapConfigSource());
        assertEquals("$Steve\\Alex: 2", text.format("greeting", "%player%", "$Steve\\Alex", "%count%", "2", "unpaired"));
        assertEquals("%player%: %count%", text.format("greeting", (String[]) null));
    }

    @Test
    public void normalizesLanguageFileNamesAndRejectsPathTraversal() {
        assertEquals("zh_CN", Translations.normalizeLanguage(null));
        assertEquals("en_US", Translations.normalizeLanguage(" en_US.YML "));
        assertEquals("lang/pt-BR.yml", Translations.resourcePath("pt-BR"));
        for (String name : new String[] {"../en_US", "lang/en_US", "..\\en_US", "en.US", "en US"}) {
            assertThrows(IllegalArgumentException.class, () -> Translations.normalizeLanguage(name));
        }
    }
}
