package pro.deta.orion.git.fileapi;

import java.util.Objects;

public record GitCommitAuthor(String name, String email, String message) {
    private static final String DEFAULT_NAME = "orion";
    private static final String DEFAULT_EMAIL = "orion@localhost";
    private static final String DEFAULT_MESSAGE = "";

    public static final GitCommitAuthor EMPTY = new GitCommitAuthor(DEFAULT_NAME, DEFAULT_EMAIL, DEFAULT_MESSAGE);

    public GitCommitAuthor {
        name = normalize(name, DEFAULT_NAME);
        email = normalize(email, DEFAULT_EMAIL);
        message = normalize(message, DEFAULT_MESSAGE);
    }

    private static String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Objects.requireNonNull(value, "value");
    }
}
