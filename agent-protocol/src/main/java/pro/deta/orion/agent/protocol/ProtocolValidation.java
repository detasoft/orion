package pro.deta.orion.agent.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

final class ProtocolValidation {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private ProtocolValidation() {
    }

    static String identifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 1-128 safe ASCII characters");
        }
        return value;
    }

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static int unsignedShort(int value, String name) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(name + " must fit an unsigned 16-bit integer");
        }
        return value;
    }

    static int terminalDimension(int value, String name) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
        return value;
    }

    static List<String> command(List<String> values) {
        Objects.requireNonNull(values, "command");
        List<String> command = List.copyOf(values);
        if (command.isEmpty() || command.getFirst().isBlank()) {
            throw new IllegalArgumentException("command must start with a non-blank executable");
        }
        for (String value : command) {
            Objects.requireNonNull(value, "command value");
        }
        return command;
    }

    static Map<String, String> stringMap(Map<String, String> values, String name) {
        Objects.requireNonNull(values, name);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = nonBlank(entry.getKey(), name + " key");
            String previous = result.put(key, Objects.requireNonNull(entry.getValue(), name + " value"));
            if (previous != null) {
                throw new IllegalArgumentException(name + " contains a duplicate key: " + key);
            }
        }
        return Map.copyOf(result);
    }

    static <T> Optional<T> optional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static byte[] utf8(String value) throws AgentProtocolException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.INVALID_FIELD,
                    "String is not valid Unicode",
                    e);
        }
    }
}
