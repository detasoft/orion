package pro.deta.orion.git.parser.v2.capability;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import static pro.deta.orion.git.parser.v2.capability.GitCapability.*;

/**
 * A named value shared by capability tokens and fetch arguments. The constructor validates the name and
 * Optional container; argument values may contain spaces or Unicode. Capability parsing, factories, and
 * wireToken additionally enforce the nonempty printable ASCII value required by capability-token syntax.
 */
public record GitCapabilityValue(String name, Optional<String> value) {
    public GitCapabilityValue {
        validateName(name);
        Objects.requireNonNull(value, "value");
    }

    public Optional<GitCapability> capability() {
        return GitCapability.findByWireName(name);
    }

    public String wireToken() {
        value.ifPresent(GitCapabilityValue::validateWireValue);
        return value.map(item -> name + "=" + item).orElse(name);
    }

    private static GitCapabilityValue custom(String name, Optional<String> value) {
        if (findByWireName(name).isPresent()) {
            throw new IllegalArgumentException("Standard capability must use its enum instance");
        }
        value.ifPresent(GitCapabilityValue::validateWireValue);
        return new GitCapabilityValue(name, value);
    }

    private static void validateName(String name) {
        String checked = Objects.requireNonNull(name, "name");
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("Capability name must not be empty");
        }
        for (int index = 0; index < checked.length(); index++) {
            char character = checked.charAt(index);
            if (Character.isWhitespace(character) || character == '=') {
                throw new IllegalArgumentException("Capability name must not contain whitespace or '='");
            }
        }
    }

    private static void validateWireValue(String value) {
        String checked = Objects.requireNonNull(value, "value");
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("Capability value must not be empty");
        }
        for (int index = 0; index < checked.length(); index++) {
            char character = checked.charAt(index);
            if (character <= 32 || character >= 127) {
                throw new IllegalArgumentException("Capability value must contain printable non-space ASCII");
            }
        }
    }

    public static GitCapabilityValue parse(String wireToken) throws IOException {
        Objects.requireNonNull(wireToken, "wireToken");

        try {
            int separator = wireToken.indexOf('=');
            if (separator < 0) {
                return new GitCapabilityValue(wireToken, Optional.empty());
            }

            String name = wireToken.substring(0, separator);
            String value = wireToken.substring(separator + 1);
            validateWireValue(value);
            return new GitCapabilityValue(name, Optional.of(value));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid capability", error);
        }
    }

    public static GitCapabilityValue parse(String wireToken, GitHashAlgorithm expectedHashAlgorithm)
            throws IOException {
        Objects.requireNonNull(expectedHashAlgorithm, "expectedHashAlgorithm");
        GitCapabilityValue capability = parse(wireToken);
        if (capability.name().equals(OBJECT_FORMAT.wireName()) && capability.value().isPresent()
                && !capability.value().orElseThrow().equals(expectedHashAlgorithm.wireName())) {
            throw new IOException("Expected object format " + expectedHashAlgorithm.wireName()
                    + ", received " + capability.value().orElseThrow());
        }
        return capability;
    }

    public static GitCapabilityValue value(String name) {
        return custom(name, Optional.empty());
    }

    public static GitCapabilityValue value(String name, String value) {
        return custom(name, Optional.of(value));
    }

    public static GitCapabilityValue value(GitCapability capability) {
        return new GitCapabilityValue(capability.wireName(), Optional.empty());
    }

    public static GitCapabilityValue value(GitCapability capability, String value) {
        validateWireValue(value);
        return new GitCapabilityValue(capability.wireName(), Optional.of(value));
    }
}
