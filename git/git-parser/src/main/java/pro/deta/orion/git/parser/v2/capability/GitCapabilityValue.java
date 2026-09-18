package pro.deta.orion.git.parser.v2.capability;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import static pro.deta.orion.git.parser.v2.capability.GitCapability.*;

public record GitCapabilityValue(String name, Optional<String> value) {
    public GitCapabilityValue {
        name = validateName(name);
        value = Objects.requireNonNull(value, "value").map(GitCapabilityValue::validateValue);
    }

    public String wireToken() {
        return value.map(item -> name + "=" + item).orElse(name);
    }

    private static GitCapabilityValue custom(String name, Optional<String> value) {
        String checkedName = validateName(name);
        if (findByWireName(checkedName).isPresent()) {
            throw new IllegalArgumentException("Standard capability must use its enum instance");
        }
        return new GitCapabilityValue(checkedName, value);
    }

    private static String validateName(String name) {
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
        return checked;
    }

    private static String validateValue(String value) {
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
        return checked;
    }

    public static GitCapabilityValue parse(String wireToken, GitHashAlgorithm expectedHashAlgorithm)
            throws IOException {
        Objects.requireNonNull(wireToken, "wireToken");
        Objects.requireNonNull(expectedHashAlgorithm, "expectedHashAlgorithm");

        try {
            int separator = wireToken.indexOf('=');
            if (separator < 0) {

                return new GitCapabilityValue(wireToken, Optional.empty());
            }

            String name = wireToken.substring(0, separator);
            String value = wireToken.substring(separator + 1);
            var capability = new GitCapabilityValue(name, Optional.of(value));

            if (name.equals(OBJECT_FORMAT.wireName()) && !value.equals(expectedHashAlgorithm.wireName())) {
                throw new IOException("Expected object format " + expectedHashAlgorithm.wireName()
                        + ", received " + value);
            }

            return capability;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid capability", error);
        }
    }

    public static GitCapabilityValue value(String name) {
        return custom(name, Optional.empty());
    }

    public static GitCapabilityValue value(String name, String value) {
        return custom(name, Optional.of(Objects.requireNonNull(value, "value")));
    }

    public static GitCapabilityValue value(GitCapability capability) {
        return new GitCapabilityValue(capability.wireName(), Optional.empty());
    }

    public static GitCapabilityValue value(GitCapability capability, String value) {
        return new GitCapabilityValue(capability.wireName(), Optional.of(Objects.requireNonNull(value, "value")));
    }
}
