package pro.deta.orion;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

record AppOptions(
        Command command,
        String configurationLocation,
        boolean helpRequested,
        List<String> applicationArguments,
        Path verificationArtifact,
        Path releasePublicKey,
        URI releasePublicKeyUrl,
        String releaseKeyFingerprint,
        Path releaseSignature,
        URI releaseSignatureUrl,
        String gpgCommand
) {
    private static final URI DEFAULT_RELEASE_PUBLIC_KEY_URL =
            URI.create("https://www.deta-it.com/.well-known/orion/release.asc");

    static AppOptions parse(String[] args) {
        return parse(args, System.getenv());
    }

    static AppOptions parse(String[] args, Map<String, String> environment) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(environment, "environment");

        if (args.length > 0) {
            if ("verify".equals(args[0])) {
                return parseVerify(args, 1, environment);
            }
            if ("run".equals(args[0])) {
                return parseRun(Command.RUN, args, 1);
            }
            if ("start".equals(args[0])) {
                return parseRun(Command.START, args, 1);
            }
            if ("restart".equals(args[0])) {
                return parseRun(Command.RESTART, args, 1);
            }
            if ("stop".equals(args[0])) {
                return parseServiceCommand(Command.STOP, args, 1);
            }
            if ("status".equals(args[0])) {
                return parseServiceCommand(Command.STATUS, args, 1);
            }
            if ("help".equals(args[0])) {
                return commandOptions(Command.RUN, null, true);
            }
        }

        return parseRun(Command.RUN, args, 0);
    }

    static String usage() {
        return """
                Usage: orion [command] [options]

                Commands:
                  run       Start Orion in the current JVM (default).
                  start     Start Orion as a background service.
                  stop      Stop the background service.
                  status    Show background service status.
                  restart   Restart the background service.
                  verify    Verify the release artifact signature.

                Options for run, start, and restart:
                  -c, --config <location>  Read configuration from a file path or classpath:// resource.
                  -h, --help               Show this help.

                Use "orion verify --help" for signature verification options.
                """;
    }

    static String verifyUsage() {
        return """
                Usage: orion verify [options]

                Options:
                  --fingerprint VALUE   Expected release signing key fingerprint.
                  --key PATH            Local ASCII-armored release public key.
                  --key-url URL         Release public key URL.
                  --signature PATH      Local detached signature.
                  --signature-url URL   Detached signature URL.
                  --artifact PATH       Artifact to verify, defaults to the current executable jar.
                  --gpg PATH            gpg executable, defaults to gpg.
                  -h, --help            Show this help.
                """;
    }

    String normalizedReleaseKeyFingerprint() {
        return normalizeFingerprint(releaseKeyFingerprint);
    }

    static String normalizeFingerprint(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static AppOptions parseRun(Command command, String[] args, int startIndex) {
        String configurationLocation = null;
        boolean helpRequested = false;

        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> helpRequested = true;
                case "--config", "-c" -> {
                    i++;
                    if (i >= args.length) {
                        throw new IllegalArgumentException(arg + " requires a value");
                    }
                    configurationLocation = setConfigurationLocation(configurationLocation, args[i]);
                }
                default -> {
                    if (arg.startsWith("--config=")) {
                        configurationLocation = setConfigurationLocation(
                                configurationLocation,
                                arg.substring("--config=".length()));
                    } else {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                }
            }
        }

        return commandOptions(command, configurationLocation, helpRequested);
    }

    private static AppOptions parseServiceCommand(Command command, String[] args, int startIndex) {
        boolean helpRequested = false;

        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> helpRequested = true;
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }

        return commandOptions(command, null, helpRequested);
    }

    private static AppOptions parseVerify(String[] args, int startIndex, Map<String, String> environment) {
        Path artifact = null;
        Path publicKey = optionalPath(environment.get("ORION_RELEASE_PUBLIC_KEY"));
        URI publicKeyUrl = optionalUri(environment.get("ORION_RELEASE_PUBLIC_KEY_URL"), DEFAULT_RELEASE_PUBLIC_KEY_URL);
        String expectedFingerprint = blankToNull(environment.get("ORION_RELEASE_KEY_FINGERPRINT"));
        Path signature = optionalPath(environment.get("ORION_RELEASE_SIGNATURE"));
        URI signatureUrl = optionalUri(environment.get("ORION_RELEASE_SIGNATURE_URL"), null);
        String gpgCommand = valueOrDefault(environment.get("ORION_GPG"), "gpg");
        boolean helpRequested = false;

        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> helpRequested = true;
                case "--artifact" -> artifact = Path.of(requireValue(args, ++i, arg));
                case "--key" -> publicKey = Path.of(requireValue(args, ++i, arg));
                case "--key-url" -> publicKeyUrl = URI.create(requireValue(args, ++i, arg));
                case "--fingerprint" -> expectedFingerprint = requireValue(args, ++i, arg);
                case "--signature" -> signature = Path.of(requireValue(args, ++i, arg));
                case "--signature-url" -> signatureUrl = URI.create(requireValue(args, ++i, arg));
                case "--gpg" -> gpgCommand = requireValue(args, ++i, arg);
                default -> throw new IllegalArgumentException("Unknown verify option: " + arg);
            }
        }

        return new AppOptions(
                Command.VERIFY,
                null,
                helpRequested,
                List.of(),
                artifact,
                publicKey,
                publicKeyUrl,
                expectedFingerprint,
                signature,
                signatureUrl,
                gpgCommand
        );
    }

    private static AppOptions commandOptions(Command command, String configurationLocation, boolean helpRequested) {
        return new AppOptions(
                command,
                configurationLocation,
                helpRequested,
                applicationArguments(configurationLocation),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static List<String> applicationArguments(String configurationLocation) {
        if (configurationLocation == null) {
            return List.of();
        }

        List<String> arguments = new ArrayList<>();
        arguments.add("--config");
        arguments.add(configurationLocation);
        return List.copyOf(arguments);
    }

    private static String setConfigurationLocation(String currentValue, String nextValue) {
        if (currentValue != null) {
            throw new IllegalArgumentException("Configuration location is already specified");
        }
        if (nextValue == null || nextValue.isBlank()) {
            throw new IllegalArgumentException("Configuration location must not be blank");
        }
        return nextValue;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        String value = args[index];
        if (value.isBlank()) {
            throw new IllegalArgumentException(option + " must not be blank");
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

    enum Command {
        RUN,
        START,
        STOP,
        STATUS,
        RESTART,
        VERIFY
    }
}
