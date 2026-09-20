package pro.deta.orion.auth.check;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.schema.acl.AccessControl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Matches slash-separated paths using literal segments, segment-local * and whole-segment **.
 * A ** segment accepts zero or more path segments; embedded ** is invalid and never matches.
 * Matching does not decode URLs or normalize paths. ACL and HTTP callers own that validation.
 */
@Slf4j
public class MatcherUtils {
    public static boolean matchExpressionValue(String pattern, String askingValue) {
        if (pattern == null || askingValue == null) {
            return false;
        }
        String[] expressions = pattern.split("/", -1);
        String[] segments = askingValue.split("/", -1);
        boolean[] matched = new boolean[segments.length + 1];
        matched[0] = true;
        for (String expression : expressions) {
            if (expression.equals("**")) {
                for (int index = 1; index <= segments.length; index++) {
                    matched[index] |= matched[index - 1];
                }
            } else {
                if (expression.contains("**")) {
                    return false;
                }
                for (int index = segments.length; index > 0; index--) {
                    matched[index] = matched[index - 1] && matchSegment(expression, segments[index - 1]);
                }
                matched[0] = false;
            }
        }
        return matched[segments.length];
    }

    private static boolean matchSegment(String pattern, String value) {
        int expression = 0;
        int character = 0;
        int star = -1;
        int retry = 0;
        while (character < value.length()) {
            if (expression < pattern.length() && pattern.charAt(expression) == '*') {
                star = expression++;
                retry = character;
            } else if (expression < pattern.length()
                    && pattern.charAt(expression) == value.charAt(character)) {
                expression++;
                character++;
            } else if (star >= 0) {
                expression = star + 1;
                character = ++retry;
            } else {
                return false;
            }
        }
        while (expression < pattern.length() && pattern.charAt(expression) == '*') {
            expression++;
        }
        return expression == pattern.length();
    }

    public static List<AccessControl.Grant> filterGrants(List<AccessControl.Grant> grants, GrantMatcher... matchers) {
        List<AccessControl.Grant> resultGrants = new ArrayList<>();
        for (AccessControl.Grant g : grants) {
            List<GrantMatcher> grantMatchers = new ArrayList<>(Arrays.asList(matchers));
            grantMatchers.removeIf(gm -> gm.matchesAny(g.getInfo()));
            if (grantMatchers.isEmpty()) // all grant matchers resolved
                resultGrants.add(g);
            else {
                log.debug("Matchers: {} not matched for grants: {}", matchers, grants);
            }
        }
        return resultGrants;
    }
}
