package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;

public class PermissionChecksTest {
    @Test
    public void matchInternalAsteriskSyntax() {
        // '*/orion', 'orion/*', '*/*', 'pre*/some'
        Assertions.assertTrue(matchExpressionValue("orion", "orion"));
        Assertions.assertTrue(matchExpressionValue("or*on", "orion"));
    }
}