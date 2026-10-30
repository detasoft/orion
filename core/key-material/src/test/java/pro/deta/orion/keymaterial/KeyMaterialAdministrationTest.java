package pro.deta.orion.keymaterial;

import pro.deta.orion.event.OrionEventManager;

import java.util.Set;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.math.BigInteger;
import java.util.Arrays;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialAdministrationTest {
    @Test
    void generatesAndPersistsSeparateAccountAndTlsKeys() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        byte[] publicKey;
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(descriptor("account", KeyMaterialPurpose.ACME_ACCOUNT), 3072);
                modification.generateKey(descriptor("identity", KeyMaterialPurpose.TLS_IDENTITY), 3072);
                modification.apply();
            });
            publicKey = material.read(new KeyMaterialId("account", 1)).publicKey().getEncoded();
        }
        try (OrionKeyMaterial material = open(store)) {
            material.require(descriptor("account", KeyMaterialPurpose.ACME_ACCOUNT));
            material.require(descriptor("identity", KeyMaterialPurpose.TLS_IDENTITY));
            assertThat(material.read(new KeyMaterialId("account",
                    1)).publicKey().getEncoded()).isEqualTo(publicKey);
            assertThat(material.inventory()).containsExactly(new KeyMaterialId("account", 1),
                    new KeyMaterialId("identity", 1));
        }
    }

    @Test
    void importsBothRsaPemFormatsAndUsesTheOriginalAccountKeyAfterReopening() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair original = generator.generateKeyPair();
        byte[] pkcs1 = PrivateKeyInfo.getInstance(original.getPrivate().getEncoded())
                .parsePrivateKey().toASN1Primitive().getEncoded();
        for (String pem : List.of(pem("PRIVATE KEY", original.getPrivate().getEncoded()),
                pem("RSA PRIVATE KEY", pkcs1))) {
            InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
            char[] input = pem.toCharArray();
            try (OrionKeyMaterial material = open(store)) {
                material.withModification(material.revision(), modification -> {
                    modification.importKey(descriptor("imported", KeyMaterialPurpose.ACME_ACCOUNT), input);
                    modification.apply();
                });
                assertThat(input).containsOnly('\0');
            }
            try (OrionKeyMaterial material = open(store)) {
                KeyPair loaded =
                        material.read(descriptor("imported", KeyMaterialPurpose.ACME_ACCOUNT).id()).keyPair();
                assertThat(loaded.getPrivate().getEncoded()).isEqualTo(original.getPrivate().getEncoded());
                assertThat(loaded.getPublic().getEncoded()).isEqualTo(original.getPublic().getEncoded());
            }
        }
    }

    @Test
    void importsCertbotJwkAndUsesTheOriginalAccountKeyAfterReopening() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair original = generator.generateKeyPair();
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        char[] input = (" \n" + jwk((RSAPrivateCrtKey) original.getPrivate())).toCharArray();
        try (OrionKeyMaterial material = open(store)) {
            material.withModification(material.revision(), modification -> {
                modification.importKey(descriptor("certbot", KeyMaterialPurpose.ACME_ACCOUNT), input);
                modification.apply();
            });
            assertThat(input).containsOnly('\0');
        }
        try (OrionKeyMaterial material = open(store)) {
            KeyPair loaded =
                    material.read(descriptor("certbot", KeyMaterialPurpose.ACME_ACCOUNT).id()).keyPair();
            assertThat(loaded.getPrivate().getEncoded()).isEqualTo(original.getPrivate().getEncoded());
            assertThat(loaded.getPublic().getEncoded()).isEqualTo(original.getPublic().getEncoded());
        }
    }

    @Test
    void rejectsMalformedOrInconsistentJwkWithoutSavingAndClearsInput() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String valid = jwk((RSAPrivateCrtKey) generator.generateKeyPair().getPrivate());
        generator.initialize(1024);
        List<String> invalidKeys = List.of("{", "{}", "{\"kty\":\"EC\"}",
                valid + " {}", valid.replace("\"kty\":\"RSA\"", "\"kty\":\"RSA\",\"kty\":\"RSA\""),
                valid.replace("\"d\":", "\"missing-d\":"),
                valid.replaceFirst("\"p\":\"[^\"]+\"", "\"p\":\"AQ\""),
                valid.replaceFirst("\"dp\":\"[^\"]+\"", "\"dp\":\"AQ\""),
                valid.replaceFirst("\"qi\":\"[^\"]+\"", "\"qi\":\"AQ\""),
                valid.replaceFirst("\"n\":\"[^\"]+\"", "\"n\":\"bad!\""),
                valid.replaceFirst("\"e\":\"[^\"]+\"", "\"e\":65537"),
                valid.replace("\"kty\":\"RSA\"", "\"kty\":\"RSA\",\"oth\":[]"),
                jwk((RSAPrivateCrtKey) generator.generateKeyPair().getPrivate()));
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        try (OrionKeyMaterial material = open(store)) {
            List<KeyMaterialId> before = material.inventory();
            for (String invalid : invalidKeys) {
                char[] input = invalid.toCharArray();
                assertThatThrownBy(() -> material.withModification(material.revision(), modification -> {
                    modification.importKey(descriptor("invalid", KeyMaterialPurpose.ACME_ACCOUNT), input);
                    modification.apply();
                })).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Invalid RSA private key").hasNoCause();
                assertThat(input).containsOnly('\0');
                assertThat(material.inventory()).isEqualTo(before);
            }
        }
        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.inventory()).isEmpty();
        }
    }

    private static String jwk(RSAPrivateCrtKey key) {
        return """
                {"kty":"RSA","n":"%s","e":"%s","d":"%s","p":"%s","q":"%s",
                 "dp":"%s","dq":"%s","qi":"%s"}
                """.formatted(unsigned(key.getModulus()), unsigned(key.getPublicExponent()),
                unsigned(key.getPrivateExponent()), unsigned(key.getPrimeP()), unsigned(key.getPrimeQ()),
                unsigned(key.getPrimeExponentP()), unsigned(key.getPrimeExponentQ()), unsigned(key.getCrtCoefficient()));
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void rejectsInvalidWeakAndNonRsaImportsWithoutChangingTheStoreAndClearsSecrets() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(1024);
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        try (OrionKeyMaterial material = open(new InMemoryKeyMaterialContentSource())) {
            List<KeyMaterialId> before = material.inventory();
            for (String invalid : List.of("not a key", pem("PRIVATE KEY", rsa.generateKeyPair().getPrivate().getEncoded()),
                    pem("PRIVATE KEY", ec.generateKeyPair().getPrivate().getEncoded()))) {
                char[] input = invalid.toCharArray();
                assertThatThrownBy(() -> material.withModification(material.revision(), modification -> {
                    modification.importKey(descriptor("invalid", KeyMaterialPurpose.ACME_ACCOUNT), input);
                    modification.apply();
                })).isInstanceOf(IllegalArgumentException.class);
                assertThat(input).containsOnly('\0');
                assertThat(material.inventory()).isEqualTo(before);
            }
        }
    }

    @Test
    void aFailedSaveInvalidatesTheOwnerAndLeavesDurableMaterialUnchanged() throws Exception {
        AtomicBoolean failWrites = new AtomicBoolean();
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource() {
            @Override
            public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
                if (failWrites.get()) throw new IOException("Storage unavailable");
                return super.write(bytes, expectedVersion);
            }
        };
        try (OrionKeyMaterial material = open(store)) {
            failWrites.set(true);
            assertThatThrownBy(() -> material.withModification(material.revision(), modification -> {
                modification.generateKey(descriptor("unsaved", KeyMaterialPurpose.ACME_ACCOUNT), 3072);
                modification.apply();
            })).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> material.inventory())
                    .isInstanceOf(IllegalStateException.class);
        }
        failWrites.set(false);
        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.inventory()).isEmpty();
        }
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(bytes) + "\n-----END " + type + "-----\n";
    }

    private static OrionKeyMaterial open(InMemoryKeyMaterialContentSource store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("password".toCharArray())) {
            return OrionKeyMaterial.open(new KeyMaterialContentStore(
                    store,
                    new KeyMaterialCodec(options),
                    new OrionEventManager()));
        }
    }

    private static KeyMaterialDescriptor descriptor(String alias, KeyMaterialPurpose purpose) {
        return new KeyMaterialDescriptor(alias, Set.of(purpose), KeyMaterialAlgorithm.RSA,
                1);
    }
}
