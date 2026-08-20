package pro.deta.orion.git.nativestorage.upload;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

public final class NativePackfileUriBuilder {
    private NativePackfileUriBuilder() {
    }

    public static String packUri(
            String baseUri,
            String repositoryPath,
            String packId) {
        String base = requireBaseUri(baseUri);
        String repository = encodeRepositoryPath(repositoryPath);
        validatePackId(packId);
        return base
                + "/"
                + repository
                + "/objects/pack/"
                + packId
                + ".pack";
    }

    private static String requireBaseUri(String baseUri) {
        String value = Objects.requireNonNull(baseUri, "baseUri").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("baseUri must not be blank");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String encodeRepositoryPath(String repositoryPath) {
        String normalized = Objects.requireNonNull(
                repositoryPath,
                "repositoryPath");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "repositoryPath must not be blank");
        }
        String[] segments = normalized.split("/");
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (segments[index].isBlank()) {
                throw new IllegalArgumentException(
                        "repositoryPath must not contain empty segments");
            }
            if (index > 0) {
                encoded.append('/');
            }
            appendEncodedSegment(encoded, segments[index]);
        }
        return encoded.toString();
    }

    private static void appendEncodedSegment(
            StringBuilder encoded,
            String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        HexFormat hex = HexFormat.of().withUpperCase();
        for (byte raw : bytes) {
            int value = raw & 0xff;
            if (unreserved(value)) {
                encoded.append((char) value);
            } else {
                encoded.append('%');
                encoded.append(hex.toHexDigits((byte) value));
            }
        }
    }

    private static boolean unreserved(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    private static void validatePackId(String packId) {
        Objects.requireNonNull(packId, "packId");
        new NativePackfileUri(packId, "https://example.test/pack");
    }
}
