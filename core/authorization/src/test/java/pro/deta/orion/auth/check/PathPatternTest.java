package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class PathPatternTest {

    @ParameterizedTest
    @CsvSource({
            "team*, team, true",
            "team*, teamone, true",
            "team*, team/one, false",
            "team/*, team/one, true",
            "team/*, team/one/api, false",
            "team/*, team, false",
            "team/*, team/, true",
            "team/**, team, true",
            "team/**, team/, true",
            "team/**, team/one, true",
            "team/**, team/one/api, true",
            "team/**, teamone, false",
            "team/**/api, team/api, true",
            "team/**/api, team/one/api, true",
            "team/**/api, team/one/two/api, true",
            "team/**/api, team/one/api/extra, false",
            "team/api*, team/api, true",
            "team/api*, team/api-v2, true",
            "team/api*, team/api/v2, false",
            "*, team/one, false",
            "**, team/one, true",
            "/session-host/**, /session-host, true",
            "/session-host/**, /session-host/linux-x86_64, true",
            "/session-host/**, /session-hostile, false",
            "/**/info/refs, /team/sub/api/info/refs, true",
            "/**/info/refs, /info/refs, true",
            "team/api.v1, team/api_v1, false",
            "team/api[1], team/api[1], true",
            "team/api[1], team/api1, false",
            "team/**suffix, team/suffix, false",
            "team/***, team/anything, false",
            "**/**/api, team/api, true",
            "team/*, /team/one, false"
    })
    void followsSegmentBoundaries(String pattern, String path, boolean expected) {
        assertEquals(expected, MatcherUtils.matchExpressionValue(pattern, path));
    }

    @Test
    void testExactMatch() {
        assertTrue(MatcherUtils.matchExpressionValue("hello", "hello"));
        assertFalse(MatcherUtils.matchExpressionValue("hello", "world"));
    }

    @Test
    void testWildcardMatch() {
        assertTrue(MatcherUtils.matchExpressionValue("hel*", "hello"));
        assertTrue(MatcherUtils.matchExpressionValue("*llo", "hello"));
        assertTrue(MatcherUtils.matchExpressionValue("h*o", "hello"));
        assertTrue(MatcherUtils.matchExpressionValue("*", "anything"));
        assertTrue(MatcherUtils.matchExpressionValue("a*b*c", "abc"));
        assertTrue(MatcherUtils.matchExpressionValue("a*b*c", "a1b2c"));
    }

    @Test
    void testNoMatch() {
        assertFalse(MatcherUtils.matchExpressionValue("hel*", "world"));
        assertFalse(MatcherUtils.matchExpressionValue("*llo", "hellx"));
        assertFalse(MatcherUtils.matchExpressionValue("h*x", "hello"));
    }

    @Test
    void testNullInputs() {
        assertFalse(MatcherUtils.matchExpressionValue(null, "hello"));
        assertFalse(MatcherUtils.matchExpressionValue("hello", null));
        assertFalse(MatcherUtils.matchExpressionValue(null, null));
    }

    @Test
    void testEmptyInputs() {
        assertTrue(MatcherUtils.matchExpressionValue("", ""));
        assertFalse(MatcherUtils.matchExpressionValue("", "hello"));
        assertFalse(MatcherUtils.matchExpressionValue("hello", ""));
    }

    @Test
    void testURLPatterns() {
        assertTrue(MatcherUtils.matchExpressionValue("/api/*", "/api/users"));
        assertTrue(MatcherUtils.matchExpressionValue("/api/*/details", "/api/users/details"));
        assertTrue(MatcherUtils.matchExpressionValue("*.jpg", "image.jpg"));
        assertFalse(MatcherUtils.matchExpressionValue("/api/*", "/other/path"));
    }
}