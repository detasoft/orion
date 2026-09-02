package pro.deta.orion.keymaterial;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class ConfigurationSecretEnvelopeCodec {
    public static final int CURRENT_VERSION = 1;
    public static final String AES_WRAP = "AESWrap";
    public static final String AES_GCM = "AES/GCM/NoPadding";
    public static final String BASE64_URL = "base64url";
    private static final String PREFIX = "orion-secret";
    private static final int FIELD_COUNT = 10;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String serialize(ConfigurationSecretEnvelope envelope)
            throws ConfigurationSecretException {
        if (envelope == null) {
            throw malformed("Configuration secret envelope must not be null", null);
        }
        requireSupported(envelope);
        return String.join(
                ".",
                PREFIX,
                field("version", Integer.toString(envelope.version())),
                field("alias", encodeText(envelope.keyAlias().value())),
                field("key-version", Long.toString(envelope.keyVersion().value())),
                field("wrap", encodeText(envelope.wrappingAlgorithm())),
                field("cipher", encodeText(envelope.encryptionAlgorithm())),
                field("encoding", encodeText(envelope.encoding())),
                field("wrapped-key", encodeBytes(envelope.wrappedDataKey())),
                field("nonce", encodeBytes(envelope.nonce())),
                field("ciphertext", encodeBytes(envelope.ciphertext())));
    }

    public ConfigurationSecretEnvelope parse(String value)
            throws ConfigurationSecretException {
        if (value == null || value.isBlank()) {
            throw malformed("Configuration secret envelope must not be empty", null);
        }
        String[] fields = value.split("\\.", -1);
        if (fields.length != FIELD_COUNT || !PREFIX.equals(fields[0])) {
            throw malformed("Configuration secret value is not an encrypted envelope", null);
        }
        ConfigurationSecretEnvelope envelope;
        try {
            envelope = new ConfigurationSecretEnvelope(
                    parseInt(value(fields[1], "version"), "version"),
                    new KeyMaterialAlias(decodeText(value(fields[2], "alias"), "key alias")),
                    new KeyMaterialVersion(parseLong(
                            value(fields[3], "key-version"), "key version")),
                    decodeText(value(fields[4], "wrap"), "wrapping algorithm"),
                    decodeText(value(fields[5], "cipher"), "encryption algorithm"),
                    decodeText(value(fields[6], "encoding"), "encoding"),
                    decodeBytes(value(fields[7], "wrapped-key"), "wrapped data key"),
                    decodeBytes(value(fields[8], "nonce"), "nonce"),
                    decodeBytes(value(fields[9], "ciphertext"), "ciphertext"));
        } catch (ConfigurationSecretException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw malformed("Configuration secret envelope contains an invalid field", e);
        }
        requireSupported(envelope);
        if (!value.equals(serialize(envelope))) {
            throw malformed("Configuration secret envelope is not canonical", null);
        }
        return envelope;
    }

    private static void requireSupported(ConfigurationSecretEnvelope envelope)
            throws ConfigurationSecretException {
        if (envelope.version() != CURRENT_VERSION) {
            throw unsupported("Configuration secret envelope version is unsupported");
        }
        if (!AES_WRAP.equals(envelope.wrappingAlgorithm())) {
            throw unsupported("Configuration secret wrapping algorithm is unsupported");
        }
        if (!AES_GCM.equals(envelope.encryptionAlgorithm())) {
            throw unsupported("Configuration secret encryption algorithm is unsupported");
        }
        if (!BASE64_URL.equals(envelope.encoding())) {
            throw unsupported("Configuration secret encoding is unsupported");
        }
    }

    private static int parseInt(String value, String field)
            throws ConfigurationSecretException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw malformed("Configuration secret " + field + " is invalid", e);
        }
    }

    private static String value(String field, String expectedName)
            throws ConfigurationSecretException {
        String prefix = expectedName + "=";
        if (!field.startsWith(prefix)) {
            throw malformed("Configuration secret envelope field order is invalid", null);
        }
        String value = field.substring(prefix.length());
        if (value.isEmpty()) {
            throw malformed("Configuration secret " + expectedName + " must not be empty", null);
        }
        return value;
    }

    private static long parseLong(String value, String field)
            throws ConfigurationSecretException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw malformed("Configuration secret " + field + " is invalid", e);
        }
    }

    private static String decodeText(String value, String field)
            throws ConfigurationSecretException {
        return new String(decodeCanonical(value, field), StandardCharsets.UTF_8);
    }

    private static byte[] decodeBytes(String value, String field)
            throws ConfigurationSecretException {
        return decodeCanonical(value, field);
    }

    private static byte[] decodeCanonical(String value, String field)
            throws ConfigurationSecretException {
        if (value == null || value.isEmpty()) {
            throw malformed("Configuration secret " + field + " must not be empty", null);
        }
        byte[] decoded;
        try {
            decoded = DECODER.decode(value);
        } catch (IllegalArgumentException e) {
            throw malformed("Configuration secret " + field + " is not base64url", e);
        }
        if (!value.equals(encodeBytes(decoded))) {
            throw malformed("Configuration secret " + field + " is not canonical base64url", null);
        }
        return decoded;
    }

    private static String encodeText(String value) {
        return encodeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeBytes(byte[] value) {
        return ENCODER.encodeToString(value);
    }

    private static String field(String name, String value) {
        return name + "=" + value;
    }

    private static ConfigurationSecretException malformed(String message, Throwable cause) {
        return new ConfigurationSecretException(
                ConfigurationSecretException.Reason.MALFORMED,
                message,
                cause);
    }

    private static ConfigurationSecretException unsupported(String message) {
        return new ConfigurationSecretException(
                ConfigurationSecretException.Reason.UNSUPPORTED,
                message);
    }
}
