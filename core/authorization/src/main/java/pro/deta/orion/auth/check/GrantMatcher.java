package pro.deta.orion.auth.check;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;

import java.util.List;

@RequiredArgsConstructor
@ToString
@Getter
public class GrantMatcher {
    private final GrantKey key;
    private final GrantKeyValueMatcher keyValueMatcher;


    public static GrantMatcher of(GrantKey key, GrantKeyValueMatcher matcher) {
        return new GrantMatcher(key, matcher);
    }

    public static GrantMatcher of(GrantKey key) {
        return new GrantMatcher(key, GrantMatcher::alwaysTrue);
    }

    private static boolean alwaysTrue(String v) {
        return true;
    }

    public boolean process(GrantExpression expression) {
        return expression.key() == key && keyValueMatcher.match(expression.value());
    }

    public boolean matchesAny(List<GrantExpression> info) {
        for (GrantExpression ge: info) {
            if (process(ge))
                return true;
        }
        return false;
    }
}