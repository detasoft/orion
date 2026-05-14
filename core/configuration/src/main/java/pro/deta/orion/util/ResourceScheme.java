package pro.deta.orion.util;

import java.util.Locale;

public sealed interface ResourceScheme permits ResourceScheme.Empty, ResourceScheme.File, ResourceScheme.Local, ResourceScheme.Other {
    Empty EMPTY = new Empty();
    File FILE = new File();
    Local LOCAL = new Local();

    String value();

    static ResourceScheme from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Resource scheme must not be empty");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case File.VALUE -> FILE;
            case Local.VALUE -> LOCAL;
            default -> new Other(normalized);
        };
    }

    static ResourceScheme fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY;
        }
        return from(value);
    }

    static Other other(String value) {
        ResourceScheme scheme = from(value);
        if (scheme instanceof Other other) {
            return other;
        }
        throw new IllegalArgumentException("Resource scheme is predefined: " + scheme.value());
    }

    record Empty() implements ResourceScheme {
        @Override
        public String value() {
            return "";
        }
    }

    record File() implements ResourceScheme {
        private static final String VALUE = "file";

        @Override
        public String value() {
            return VALUE;
        }
    }

    record Local() implements ResourceScheme {
        private static final String VALUE = "local";

        @Override
        public String value() {
            return VALUE;
        }
    }

    record Other(String value) implements ResourceScheme {
        public Other {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Resource scheme must not be empty");
            }
            value = value.toLowerCase(Locale.ROOT);
        }
    }
}
