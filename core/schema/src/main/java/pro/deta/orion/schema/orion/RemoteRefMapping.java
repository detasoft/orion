package pro.deta.orion.schema.orion;

import java.util.Objects;

public record RemoteRefMapping(String source, String destination) {
    private static final String HEADS = "refs/heads/";
    private static final String TAGS = "refs/tags/";

    public RemoteRefMapping {
        source = requireFullRef(source, "remote ref mapping source");
        destination = requireFullRef(destination, "remote ref mapping destination");
        if (source.indexOf('*') >= 0 != (destination.indexOf('*') >= 0)) {
            throw new IllegalArgumentException("remote ref mapping wildcards must match");
        }
        if (!namespace(source).equals(namespace(destination))) {
            throw new IllegalArgumentException("remote ref mapping must preserve the ref namespace");
        }
    }

    public static RemoteRefMapping allBranches() {
        return new RemoteRefMapping(HEADS + "*", HEADS + "*");
    }

    static String requireConcreteBranch(String value, String description) {
        String ref = requireFullRef(value, description);
        if (!ref.startsWith(HEADS) || ref.indexOf('*') >= 0) {
            throw new IllegalArgumentException(description + " must be a concrete branch ref: " + value);
        }
        return ref;
    }

    static String requireFullRef(String value, String description) {
        Objects.requireNonNull(value, description);
        if ((!value.startsWith(HEADS) && !value.startsWith(TAGS))
                || value.endsWith("/")
                || value.endsWith(".")
                || value.contains("//")
                || value.contains("..")
                || value.contains("@{")
                || value.indexOf('\\') >= 0
                || containsForbiddenCharacter(value)
                || containsInvalidComponent(value)
                || wildcardCount(value) > 1) {
            throw new IllegalArgumentException(description + " is not a canonical full ref: " + value);
        }
        return value;
    }

    private static String namespace(String ref) {
        return ref.startsWith(HEADS) ? HEADS : TAGS;
    }

    private static boolean containsForbiddenCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < ' '
                    || character == 0x7f
                    || Character.isWhitespace(character)
                    || character == '~'
                    || character == '^'
                    || character == ':'
                    || character == '?'
                    || character == '[') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInvalidComponent(String value) {
        int componentStart = 0;
        for (int index = 0; index <= value.length(); index++) {
            if (index == value.length() || value.charAt(index) == '/') {
                String component = value.substring(componentStart, index);
                if (component.startsWith(".") || component.endsWith(".lock")) {
                    return true;
                }
                componentStart = index + 1;
            }
        }
        return false;
    }

    private static int wildcardCount(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '*') {
                count++;
            }
        }
        return count;
    }
}
