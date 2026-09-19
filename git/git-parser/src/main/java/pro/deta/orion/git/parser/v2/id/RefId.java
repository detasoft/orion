package pro.deta.orion.git.parser.v2.id;

import java.util.Objects;

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
