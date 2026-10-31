package pro.deta.orion.util;

import java.util.Objects;

public record VcChangeAuthor(String name, String email) {
    private static final String DEFAULT_NAME = "orion";
    private static final String DEFAULT_EMAIL = "orion@localhost";

    public static final VcChangeAuthor EMPTY = new VcChangeAuthor(DEFAULT_NAME, DEFAULT_EMAIL);

    public VcChangeAuthor {
        name = normalize(name, DEFAULT_NAME);
        email = normalize(email, DEFAULT_EMAIL);
    }

    private static String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Objects.requireNonNull(value, "value");
    }
}
