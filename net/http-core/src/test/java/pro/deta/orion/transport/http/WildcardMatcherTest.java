package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WildcardMatcherTest {

    @Test
    void testExactMatch() {
        assertTrue(WildcardMatcher.matches("hello", "hello"));
        assertFalse(WildcardMatcher.matches("hello", "world"));
    }

    @Test
    void testWildcardMatch() {
        assertTrue(WildcardMatcher.matches("hel*", "hello"));
        assertTrue(WildcardMatcher.matches("*llo", "hello"));
        assertTrue(WildcardMatcher.matches("h*o", "hello"));
        assertTrue(WildcardMatcher.matches("*", "anything"));
        assertTrue(WildcardMatcher.matches("a*b*c", "abc"));
        assertTrue(WildcardMatcher.matches("a*b*c", "a1b2c"));
    }

    @Test
    void testNoMatch() {
        assertFalse(WildcardMatcher.matches("hel*", "world"));
        assertFalse(WildcardMatcher.matches("*llo", "hellx"));
        assertFalse(WildcardMatcher.matches("h*x", "hello"));
    }

    @Test
    void testNullInputs() {
        assertFalse(WildcardMatcher.matches(null, "hello"));
        assertFalse(WildcardMatcher.matches("hello", null));
        assertFalse(WildcardMatcher.matches(null, null));
    }

    @Test
    void testEmptyInputs() {
        assertTrue(WildcardMatcher.matches("", ""));
        assertFalse(WildcardMatcher.matches("", "hello"));
        assertFalse(WildcardMatcher.matches("hello", ""));
    }

    @Test
    void testURLPatterns() {
        assertTrue(WildcardMatcher.matches("/api/*", "/api/users"));
        assertTrue(WildcardMatcher.matches("/api/*/details", "/api/users/details"));
        assertTrue(WildcardMatcher.matches("*.jpg", "image.jpg"));
        assertFalse(WildcardMatcher.matches("/api/*", "/other/path"));
    }
}