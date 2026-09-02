package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationSecretEnvelopeCodecTest {
    private final ConfigurationSecretEnvelopeCodec codec = new ConfigurationSecretEnvelopeCodec();

    @Test
    void serializesAndParsesCanonicalEnvelope() throws Exception {
        ConfigurationSecretEnvelope envelope = envelope();

        String serialized = codec.serialize(envelope);
        ConfigurationSecretEnvelope parsed = codec.parse(serialized);

        assertThat(serialized).contains(".alias=").contains(".ciphertext=");
        assertThat(codec.serialize(parsed)).isEqualTo(serialized);
        assertThat(parsed.version()).isEqualTo(1);
        assertThat(parsed.keyAlias()).isEqualTo(new KeyMaterialAlias("configuration-v1"));
        assertThat(parsed.keyVersion()).isEqualTo(new KeyMaterialVersion(1));
        assertThat(parsed.wrappingAlgorithm()).isEqualTo("AESWrap");
        assertThat(parsed.encryptionAlgorithm()).isEqualTo("AES/GCM/NoPadding");
        assertThat(parsed.encoding()).isEqualTo("base64url");
        assertThat(parsed.wrappedDataKey()).containsExactly(1, 2, 3);
        assertThat(parsed.nonce()).hasSize(12);
        assertThat(parsed.ciphertext()).containsExactly(4, 5, 6);
    }

    @Test
    void ownsEnvelopeByteArrays() {
        byte[] wrappedDataKey = bytes(1, 2, 3);
        byte[] nonce = twelveByteNonce();
        byte[] ciphertext = bytes(4, 5, 6);
        ConfigurationSecretEnvelope envelope = new ConfigurationSecretEnvelope(
                1,
                new KeyMaterialAlias("configuration-v1"),
                new KeyMaterialVersion(1),
                "AESWrap",
                "AES/GCM/NoPadding",
                "base64url",
                wrappedDataKey,
                nonce,
                ciphertext);

        wrappedDataKey[0] = 9;
        nonce[0] = 9;
        ciphertext[0] = 9;
        byte[] returnedWrappedKey = envelope.wrappedDataKey();
        byte[] returnedNonce = envelope.nonce();
        byte[] returnedCiphertext = envelope.ciphertext();
        returnedWrappedKey[0] = 8;
        returnedNonce[0] = 8;
        returnedCiphertext[0] = 8;

        assertThat(envelope.wrappedDataKey()).containsExactly(1, 2, 3);
        assertThat(envelope.nonce()).containsExactly(twelveByteNonce());
        assertThat(envelope.ciphertext()).containsExactly(4, 5, 6);
    }

    @Test
    void rejectsPlaintextAndMalformedEnvelopeFields() throws Exception {
        String valid = codec.serialize(envelope());

        assertFailure("database-password", ConfigurationSecretException.Reason.MALFORMED);
        assertFailure("orion-secret.1", ConfigurationSecretException.Reason.MALFORMED);
        assertFailure(replace(valid, 3, "key-version=not-a-number"),
                ConfigurationSecretException.Reason.MALFORMED);
        assertFailure(replace(valid, 7, "wrapped-key="),
                ConfigurationSecretException.Reason.MALFORMED);
        assertFailure(replace(valid, 2, "alias=" + field("configuration-v1") + "="),
                ConfigurationSecretException.Reason.MALFORMED);
    }

    @Test
    void rejectsUnsupportedEnvelopeMetadata() throws Exception {
        String valid = codec.serialize(envelope());

        assertFailure(replace(valid, 1, "version=2"), ConfigurationSecretException.Reason.UNSUPPORTED);
        assertFailure(replace(valid, 4, "wrap=" + field("AES/KWP/NoPadding")),
                ConfigurationSecretException.Reason.UNSUPPORTED);
        assertFailure(replace(valid, 5, "cipher=" + field("AES/CBC/PKCS5Padding")),
                ConfigurationSecretException.Reason.UNSUPPORTED);
        assertFailure(replace(valid, 6, "encoding=" + field("base64")),
                ConfigurationSecretException.Reason.UNSUPPORTED);
    }

    @Test
    void rejectsReorderedDuplicateAndUnknownEnvelopeFields() throws Exception {
        String valid = codec.serialize(envelope());

        assertFailure(swap(valid, 7, 9), ConfigurationSecretException.Reason.MALFORMED);
        assertFailure(replace(valid, 8, fieldAt(valid, 7)),
                ConfigurationSecretException.Reason.MALFORMED);
        assertFailure(replace(valid, 8, "unknown=" + valueAt(valid, 8)),
                ConfigurationSecretException.Reason.MALFORMED);
    }

    @Test
    void rejectsInvalidEnvelopeValuesWithoutIncludingThemInErrors() {
        byte[] sensitive = "do-not-report".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ConfigurationSecretEnvelope(
                1,
                new KeyMaterialAlias("configuration-v1"),
                new KeyMaterialVersion(1),
                "AESWrap",
                "AES/GCM/NoPadding",
                "base64url",
                sensitive,
                new byte[0],
                sensitive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("do-not-report");
    }

    private void assertFailure(String value, ConfigurationSecretException.Reason reason) {
        assertThatThrownBy(() -> codec.parse(value))
                .isInstanceOfSatisfying(
                        ConfigurationSecretException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessageNotContaining("database-password")
                .hasMessageNotContaining("do-not-report");
    }

    private static ConfigurationSecretEnvelope envelope() {
        return new ConfigurationSecretEnvelope(
                1,
                new KeyMaterialAlias("configuration-v1"),
                new KeyMaterialVersion(1),
                "AESWrap",
                "AES/GCM/NoPadding",
                "base64url",
                bytes(1, 2, 3),
                twelveByteNonce(),
                bytes(4, 5, 6));
    }

    private static String replace(String value, int index, String replacement) {
        String[] fields = value.split("\\.", -1);
        fields[index] = replacement;
        return String.join(".", fields);
    }

    private static String swap(String value, int first, int second) {
        String[] fields = value.split("\\.", -1);
        String held = fields[first];
        fields[first] = fields[second];
        fields[second] = held;
        return String.join(".", fields);
    }

    private static String fieldAt(String value, int index) {
        return value.split("\\.", -1)[index];
    }

    private static String valueAt(String value, int index) {
        String field = fieldAt(value, index);
        int separator = field.indexOf('=');
        return separator < 0 ? field : field.substring(separator + 1);
    }

    private static String field(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] twelveByteNonce() {
        return bytes(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
