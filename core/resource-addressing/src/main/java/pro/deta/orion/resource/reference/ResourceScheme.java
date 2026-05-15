package pro.deta.orion.resource.reference;

import java.util.Locale;

public record ResourceScheme(String value) {
    public static final ResourceScheme EMPTY = new ResourceScheme("");
    public static final ResourceScheme FILE = new ResourceScheme("file");
    public static final ResourceScheme CONTENT = new ResourceScheme("content");
    public static final ResourceScheme GIT = new ResourceScheme("git");
    public static final ResourceScheme GIT_FILE = new ResourceScheme("git+file");
    public static final ResourceScheme GIT_SSH = new ResourceScheme("git+ssh");
    public static final ResourceScheme GIT_HTTP = new ResourceScheme("git+http");
    public static final ResourceScheme GIT_HTTPS = new ResourceScheme("git+https");
    public static final ResourceScheme SSH = new ResourceScheme("ssh");
    public static final ResourceScheme HTTP = new ResourceScheme("http");
    public static final ResourceScheme HTTPS = new ResourceScheme("https");
    public static final ResourceScheme S3 = new ResourceScheme("s3");

    public ResourceScheme {
        if (value == null) {
            throw new IllegalArgumentException("Resource scheme must not be null");
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    public static ResourceScheme of(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY;
        }
        if (!isScheme(value)) {
            throw new IllegalArgumentException("Invalid resource scheme: " + value);
        }
        return new ResourceScheme(value);
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    static boolean isScheme(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!Character.isLetter(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }
}
