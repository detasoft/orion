package pro.deta.orion.git.client;

import java.util.Objects;

final class GitClientValidation {
    static final String NULL_ID = "0".repeat(40);

    private GitClientValidation() {
    }

    static String requireObjectId(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.length() != 40) {
            throw new IllegalArgumentException(
                    name + " must contain 40 hexadecimal digits");
        }
        for (int index = 0; index < checked.length(); index++) {
            char character = checked.charAt(index);
            if (!isHexadecimal(character)) {
                throw new IllegalArgumentException(
                        name + " must contain 40 hexadecimal digits");
            }
        }
        return checked;
    }

    static String requireRefName(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (!checked.startsWith("refs/")
                || checked.length() == "refs/".length()
                || checked.endsWith("/")
                || checked.contains("//")
                || checked.contains("..")
                || checked.contains("@{")) {
            throw new IllegalArgumentException(name + " must be a full Git ref name");
        }
        for (int index = 0; index < checked.length(); index++) {
            char character = checked.charAt(index);
            if (character <= 0x20
                    || character >= 0x7f
                    || "~^:?*[\\".indexOf(character) >= 0) {
                throw new IllegalArgumentException(name + " must be a full Git ref name");
            }
        }
        return checked;
    }

    static String requireAdvertisedRefName(String value, String name) {
        if ("HEAD".equals(value)) {
            return value;
        }
        return requireRefName(value, name);
    }

    private static boolean isHexadecimal(char value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }
}
