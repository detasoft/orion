package pro.deta.orion.git.parser.v2.id;

import java.util.Objects;

/**
 * Identifies a ref by its exact name within a repository, independently of the object it points to.
 * Preserves case and spelling; rejects blank names, ASCII controls, space, and Git-forbidden characters.
 * This is basic character validation; structural ref syntax and operation-specific checks remain at the boundary.
 * The repository context supplies repository identity, so this value contains only the ref name.
 */
public record RefId(String value) {
    public RefId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Ref name must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 32 || character == 127 || "~^:?*[\\".indexOf(character) >= 0) {
                throw new IllegalArgumentException("Ref name contains a forbidden character: " + (int) character);
            }
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
