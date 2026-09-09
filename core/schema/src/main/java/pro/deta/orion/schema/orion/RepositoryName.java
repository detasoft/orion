package pro.deta.orion.schema.orion;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class RepositoryName {
    private static final String GIT_SUFFIX = ".git";

    private final String value;

    private RepositoryName(String value) {
        this.value = value;
    }

    public static RepositoryName parse(String input) {
        return canonicalize(input, false);
    }

    public static RepositoryName fromGitPath(String input) {
        return canonicalize(input, true);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RepositoryName that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    private static RepositoryName canonicalize(String input, boolean gitPath) {
        if (input == null) {
            throw invalid("null");
        }
        String value = percentDecode(input).replace('\\', '/');
        if (gitPath) {
            if (value.startsWith("/")) {
                value = value.substring(1);
            }
            if (value.endsWith(GIT_SUFFIX)) {
                value = value.substring(0, value.length() - GIT_SUFFIX.length());
            }
        }
        validate(value);
        return new RepositoryName(value);
    }

    private static String percentDecode(String input) {
        StringBuilder decoded = new StringBuilder(input.length());
        for (int index = 0; index < input.length();) {
            if (input.charAt(index) != '%') {
                decoded.append(input.charAt(index));
                index++;
                continue;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index < input.length() && input.charAt(index) == '%') {
                if (index + 2 >= input.length()) {
                    throw invalid(input);
                }
                int high = asciiHexDigit(input.charAt(index + 1));
                int low = asciiHexDigit(input.charAt(index + 2));
                if (high < 0 || low < 0) {
                    throw invalid(input);
                }
                bytes.write((high << 4) | low);
                index += 3;
            }
            decoded.append(decodeUtf8(bytes.toByteArray(), input));
        }
        return decoded.toString();
    }

    private static int asciiHexDigit(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    private static String decodeUtf8(byte[] bytes, String input) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw invalid(input, error);
        }
    }

    private static void validate(String value) {
        if (value.isEmpty() || value.startsWith("/") || value.endsWith("/") || value.endsWith(GIT_SUFFIX)) {
            throw invalid(value);
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            IdentifierRules.requireCanonical(segment, "repository name segment");
        }
    }

    private static IllegalArgumentException invalid(String input) {
        return new IllegalArgumentException("Invalid repository name: " + input);
    }

    private static IllegalArgumentException invalid(String input, Exception cause) {
        return new IllegalArgumentException("Invalid repository name: " + input, cause);
    }
}
