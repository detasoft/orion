package pro.deta.orion.git.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record RepositoryStorageLocator(String scheme, String location, Map<String, String> auth) {
    public RepositoryStorageLocator {
        scheme = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        location = Objects.requireNonNull(location, "location");
        Map<String, String> authCopy = new LinkedHashMap<>();
        if (auth != null) {
            authCopy.putAll(auth);
        }
        auth = Collections.unmodifiableMap(authCopy);
    }

    public static RepositoryStorageLocator parse(String location) {
        return parse(location, Map.of());
    }

    public static RepositoryStorageLocator parse(String location, Map<String, String> auth) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Repository storage location must not be empty");
        }
        String scheme = explicitScheme(location);
        if (scheme == null || scheme.isBlank()) {
            scheme = "file";
        }
        return new RepositoryStorageLocator(scheme, location, auth);
    }

    private static String explicitScheme(String location) {
        int colon = location.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        char first = location.charAt(0);
        if (!isSchemeFirstChar(first)) {
            return null;
        }
        for (int i = 1; i < colon; i++) {
            char c = location.charAt(i);
            if (!isSchemeChar(c)) {
                return null;
            }
        }
        return location.substring(0, colon);
    }

    private static boolean isSchemeFirstChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean isSchemeChar(char c) {
        return isSchemeFirstChar(c) || c >= '0' && c <= '9' || c == '+' || c == '-' || c == '.';
    }
}
