package pro.deta.orion.bootstrap;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

record ReleaseVerificationOptions(
        Path artifact,
        Path publicKey,
        URI publicKeyUrl,
        String expectedFingerprint,
        Path signature,
        URI signatureUrl,
        String gpgCommand,
        boolean helpRequested
) {
    private static final URI DEFAULT_PUBLIC_KEY_URL =
            URI.create("https://www.deta-it.com/.well-known/orion/release.asc");

    static ReleaseVerificationOptions parse(String[] arguments, Map<String, String> environment, Path defaultArtifact) {
        Path artifact = defaultArtifact;
        Path publicKey = optionalPath(environment.get("ORION_RELEASE_PUBLIC_KEY"));
        URI publicKeyUrl = optionalUri(environment.get("ORION_RELEASE_PUBLIC_KEY_URL"), DEFAULT_PUBLIC_KEY_URL);
        String expectedFingerprint = blankToNull(environment.get("ORION_RELEASE_KEY_FINGERPRINT"));
        Path signature = optionalPath(environment.get("ORION_RELEASE_SIGNATURE"));
        URI signatureUrl = optionalUri(environment.get("ORION_RELEASE_SIGNATURE_URL"), null);
        String gpgCommand = valueOrDefault(environment.get("ORION_GPG"), "gpg");
        boolean helpRequested = false;

        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i];
            switch (argument) {
                case "--help", "-h" -> helpRequested = true;
                case "--artifact" -> artifact = Path.of(requireValue(arguments, ++i, argument));
                case "--key" -> publicKey = Path.of(requireValue(arguments, ++i, argument));
                case "--key-url" -> publicKeyUrl = URI.create(requireValue(arguments, ++i, argument));
                case "--fingerprint" -> expectedFingerprint = requireValue(arguments, ++i, argument);
                case "--signature" -> signature = Path.of(requireValue(arguments, ++i, argument));
                case "--signature-url" -> signatureUrl = URI.create(requireValue(arguments, ++i, argument));
                case "--gpg" -> gpgCommand = requireValue(arguments, ++i, argument);
                default -> throw new IllegalArgumentException("Unknown verify option: " + argument);
            }
        }

        return new ReleaseVerificationOptions(
                artifact,
                publicKey,
                publicKeyUrl,
                expectedFingerprint,
                signature,
                signatureUrl,
                gpgCommand,
                helpRequested
        );
    }

    String normalizedFingerprint() {
        return normalizeFingerprint(expectedFingerprint);
    }

    static String normalizeFingerprint(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String requireValue(String[] arguments, int index, String option) {
        if (index >= arguments.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        String value = arguments[index];
        if (value.isBlank()) {
            throw new IllegalArgumentException("Blank value for " + option);
        }
        return value;
    }

    private static Path optionalPath(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Path.of(normalized);
    }

    private static URI optionalUri(String value, URI defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : URI.create(normalized);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
