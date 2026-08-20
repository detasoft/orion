package pro.deta.orion.git.nativestorage.upload;

import java.util.Locale;
import java.util.Objects;

public record NativePackfileUri(
        String packHash,
        String uri) {

    public NativePackfileUri {
        validatePackHash(packHash);
        validateUri(uri);
    }

    public String protocol() {
        return uri.substring(0, uri.indexOf(':'))
                .toLowerCase(Locale.ROOT);
    }

    private static void validatePackHash(String packHash) {
        Objects.requireNonNull(packHash, "packHash");
        if (packHash.length() != 40) {
            throw new IllegalArgumentException(
                    "Packfile URI hash must contain 40 hexadecimal digits");
        }
        for (int index = 0; index < packHash.length(); index++) {
            char value = packHash.charAt(index);
            boolean hexadecimal = value >= '0' && value <= '9'
                    || value >= 'a' && value <= 'f'
                    || value >= 'A' && value <= 'F';
            if (!hexadecimal) {
                throw new IllegalArgumentException(
                        "Packfile URI hash must contain 40 hexadecimal digits");
            }
        }
    }

    private static void validateUri(String uri) {
        Objects.requireNonNull(uri, "uri");
        if (uri.isEmpty()) {
            throw new IllegalArgumentException(
                    "Packfile URI must not be empty");
        }
        int separator = uri.indexOf(':');
        if (separator <= 0 || !validScheme(uri, separator)) {
            throw new IllegalArgumentException(
                    "Packfile URI must have a valid protocol scheme");
        }
        for (int index = 0; index < uri.length(); index++) {
            char value = uri.charAt(index);
            if (value <= 0x20 || value >= 0x7f) {
                throw new IllegalArgumentException(
                        "Packfile URI must contain printable ASCII without spaces");
            }
        }
    }

    static boolean validProtocol(String protocol) {
        Objects.requireNonNull(protocol, "protocol");
        return validScheme(protocol, protocol.length());
    }

    private static boolean validScheme(String value, int endExclusive) {
        if (endExclusive == 0) {
            return false;
        }
        char first = value.charAt(0);
        if (!isAsciiLetter(first)) {
            return false;
        }
        for (int index = 1; index < endExclusive; index++) {
            char character = value.charAt(index);
            if (!isAsciiLetter(character)
                    && (character < '0' || character > '9')
                    && character != '+'
                    && character != '.'
                    && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z';
    }
}
