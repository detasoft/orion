package pro.deta.orion.git.parser.v2.id;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RefIdTest {
    @Test
    void preservesRefSpellingAndUsesNameForEquality() {
        RefId ref = new RefId("refs/heads/Main");
        assertEquals("refs/heads/Main", ref.value());
        assertEquals("refs/heads/Main", ref.toString());
        assertEquals(ref, new RefId("refs/heads/Main"));
        assertEquals("found", Map.of(ref, "found").get(new RefId("refs/heads/Main")));
        assertNotEquals(ref, new RefId("refs/heads/main"));
        assertNotEquals(ref, new RefId("refs/tags/Main"));
    }

    @Test
    void representsHeadWithoutConfusingNameWithObjectIdentity() {
        assertEquals("HEAD", new RefId("HEAD").value());
        assertNotEquals(new RefId("HEAD"), new RefId("refs/heads/main"));
    }

    @Test
    void rejectsMissingNames() {
        assertThrows(NullPointerException.class, () -> new RefId(null));
        for (String value : new String[] {"", " ", "\t\n"}) {
            assertThrows(IllegalArgumentException.class, () -> new RefId(value));
        }
    }

    @Test
    void rejectsForbiddenCharactersAnywhereInName() {
        for (char character : " ~^:?*[\\".toCharArray()) {
            assertForbidden(character);
        }
        for (char character = 0; character < 32; character++) {
            assertForbidden(character);
        }
        assertForbidden((char) 127);
    }

    @Test
    void preservesAllowedPunctuationAndUnicode() {
        String value = "refs/heads/Ветка-1_+@draft{test}].v2";
        assertEquals(value, new RefId(value).value());
        assertDoesNotThrow(() -> new RefId(value).requireFullName());
    }

    @Test
    void requiresAFullRefWithoutInvalidPathComponents() {
        for (String value : new String[]{"HEAD", "main", "refs/", "refs//main", "refs/heads/.hidden",
                "refs/heads/main.lock", "refs/heads/a..b", "refs/heads/a@{b", "refs/heads/main.",
                "refs/heads/main/", "refs/heads/hidden.lock/main"}) {
            assertThrows(IllegalArgumentException.class, () -> new RefId(value).requireFullName(), value);
        }
        assertDoesNotThrow(() -> new RefId("refs/heads/main").requireFullName());
    }

    private void assertForbidden(char character) {
        for (String value : new String[] {
                character + "refs/heads/main", "refs/heads/ma" + character + "in", "refs/heads/main" + character
        }) {
            assertThrows(IllegalArgumentException.class, () -> new RefId(value),
                    "Must reject character " + (int) character);
        }
    }
}
