package pro.deta.orion.auth.check;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import pro.deta.orion.acl.schema.AccessControl;

import java.util.List;

@RequiredArgsConstructor
@ToString
@Getter
public class GrantMatcher {
    private final AccessControl.GrantKey key;
    private final GrantKeyValueMatcher keyValueMatcher;


    public static GrantMatcher of(AccessControl.GrantKey key, GrantKeyValueMatcher matcher) {
        return new GrantMatcher(key, matcher);
    }

    public static GrantMatcher of(AccessControl.GrantKey key) {
        return new GrantMatcher(key, GrantMatcher::alwaysTrue);
    }

    private static boolean alwaysTrue(String v) {
        return true;
    }

    public boolean process(AccessControl.GrantExpression expression) {
        return expression.getKey() == key && keyValueMatcher.match(expression.getValue());
    }

    public boolean matchesAny(List<AccessControl.GrantExpression> info) {
        for (AccessControl.GrantExpression ge: info) {
            if (process(ge))
                return true;
        }
        return false;
    }
}