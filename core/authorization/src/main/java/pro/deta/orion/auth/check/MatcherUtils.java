package pro.deta.orion.auth.check;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.acl.schema.AccessControl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
public class MatcherUtils {
    private final static ConcurrentHashMap<String, Pattern> patterns = new ConcurrentHashMap<>();
    private static final String WILDCARD_SEGMENT_REGEX = "[A-Za-z0-9_-]*";

    public static boolean matchExpressionValue(String pattern, String askingValue) {
        pattern = pattern.replaceAll("[^a-zA-Z0-9_\\-\\/\\*]", "_");
        String reg = pattern.replace("*", WILDCARD_SEGMENT_REGEX);
        Pattern p = patterns.computeIfAbsent(reg, Pattern::compile);
        synchronized (p) {
            return p.matcher(askingValue).matches();
        }
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
