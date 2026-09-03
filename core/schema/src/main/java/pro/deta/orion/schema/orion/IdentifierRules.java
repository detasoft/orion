package pro.deta.orion.schema.orion;

import java.util.Objects;
import java.util.regex.Pattern;

final class IdentifierRules {
    private static final Pattern CANONICAL_IDENTIFIER =
            Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*");

    private IdentifierRules() {
    }

    static String requireCanonical(String value, String description) {
        Objects.requireNonNull(value, description);
        if (!CANONICAL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    description + " must be a canonical lowercase identifier: " + value);
        }
        return value;
    }
}
