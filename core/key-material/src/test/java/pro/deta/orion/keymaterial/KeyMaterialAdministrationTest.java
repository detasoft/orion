package pro.deta.orion.keymaterial;

import pro.deta.orion.keymaterial.KeyMaterialTestRuntime.TestBlobStorage;

import pro.deta.orion.util.VcChangeDescription;

import pro.deta.orion.util.VcRevision;

import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.revision;

import pro.deta.orion.storage.InMemoryBlobStorage;

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
    @org.junit.jupiter.api.extension.RegisterExtension
    final KeyMaterialTestRuntime runtime = new KeyMaterialTestRuntime();

    @Test
    void rejectsInvalidCreationMetadataWithoutPublishingMaterial() throws Exception {
        InMemoryBlobStorage source = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial material = open(source)) {
            KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
            try {
                assertThatThrownBy(() -> modification.generateKey("account",
                        Set.of(KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialPurpose.TLS_IDENTITY),
                        KeyMaterialAlgorithm.RSA, 2048)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("ACME_ACCOUNT");
                assertThatThrownBy(() -> modification.generateKey("cipher",
                        Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.RSA, 2048))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AES");
                assertThatThrownBy(() -> modification.generateKey("signing",
                        Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.AES, 256))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AES");
                assertThatThrownBy(() -> modification.generateKey("empty", Set.of(), KeyMaterialAlgorithm.RSA, 2048))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("purposes");
                modification.commit(VcChangeDescription.EMPTY);
                assertThat(material.inventory()).isEmpty();
                assertThat(revision(source)).isEqualTo("1");
            } finally {
                modification.close();
            }
        }
    }

    @Test
    void generatesAndPersistsSeparateAccountAndTlsKeys() throws Exception {
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        byte[] publicKey;
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey(("account"), Set.of((KeyMaterialPurpose.ACME_ACCOUNT)), KeyMaterialAlgorithm.RSA, 3072);
                modification.generateKey(("identity"), Set.of((KeyMaterialPurpose.TLS_IDENTITY)), KeyMaterialAlgorithm.RSA, 3072);
                modification.commit(VcChangeDescription.EMPTY);
            }
            publicKey = material.read(new KeyMaterialId("account", 1)).publicKey().getEncoded();
        }
        try (OrionKeyMaterial material = open(store)) {
            material.require(new KeyMaterialId("account", 1), Set.of((KeyMaterialPurpose.ACME_ACCOUNT)), KeyMaterialAlgorithm.RSA);
            material.require(new KeyMaterialId("identity", 1), Set.of((KeyMaterialPurpose.TLS_IDENTITY)), KeyMaterialAlgorithm.RSA);
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
            InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
            char[] input = pem.toCharArray();
            try (OrionKeyMaterial material = open(store)) {
                try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                    modification.importKey(("imported"), Set.of((KeyMaterialPurpose.ACME_ACCOUNT)), KeyMaterialAlgorithm.RSA, input);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                assertThat(input).containsOnly('\0');
            }
            try (OrionKeyMaterial material = open(store)) {
                KeyPair loaded =
                        material.read(new KeyMaterialId("imported", 1)).keyPair();
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
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        char[] input = (" \n" + jwk((RSAPrivateCrtKey) original.getPrivate())).toCharArray();
        try (OrionKeyMaterial material = open(store)) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.importKey(("certbot"), Set.of((KeyMaterialPurpose.ACME_ACCOUNT)), KeyMaterialAlgorithm.RSA, input);
                modification.commit(VcChangeDescription.EMPTY);
            }
            assertThat(input).containsOnly('\0');
        }
        try (OrionKeyMaterial material = open(store)) {
            KeyPair loaded =
                    material.read(new KeyMaterialId("certbot", 1)).keyPair();
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
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events);
        try (OrionKeyMaterial material = open(store)) {
            List<KeyMaterialId> before = material.inventory();
            for (String invalid : invalidKeys) {
                char[] input = invalid.toCharArray();
                assertThatThrownBy(
                                () -> {
                                    try (KeyMaterialOperation modification =
                                            material.open(
                                                    new VcRevision.Stored(material.revision()))) {
                                        modification.importKey(
                                                ("invalid"),
                                                Set.of((KeyMaterialPurpose.ACME_ACCOUNT)),
                                                KeyMaterialAlgorithm.RSA,
                                                input);
                                        modification.commit(VcChangeDescription.EMPTY);
                                    }
                                })
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Invalid RSA private key")
                        .hasNoCause();
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
        try (OrionKeyMaterial material = open(new TestBlobStorage(runtime.events))) {
            List<KeyMaterialId> before = material.inventory();
            for (String invalid : List.of("not a key", pem("PRIVATE KEY", rsa.generateKeyPair().getPrivate().getEncoded()),
                    pem("PRIVATE KEY", ec.generateKeyPair().getPrivate().getEncoded()))) {
                char[] input = invalid.toCharArray();
                assertThatThrownBy(
                                () -> {
                                    try (KeyMaterialOperation modification =
                                            material.open(
                                                    new VcRevision.Stored(material.revision()))) {
                                        modification.importKey(
                                                ("invalid"),
                                                Set.of((KeyMaterialPurpose.ACME_ACCOUNT)),
                                                KeyMaterialAlgorithm.RSA,
                                                input);
                                        modification.commit(VcChangeDescription.EMPTY);
                                    }
                                })
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(input).containsOnly('\0');
                assertThat(material.inventory()).isEqualTo(before);
            }
        }
    }

    @Test
    void aFailedSaveInvalidatesTheOwnerAndLeavesDurableMaterialUnchanged() throws Exception {
        AtomicBoolean failWrites = new AtomicBoolean();
        InMemoryBlobStorage store = new TestBlobStorage(runtime.events) {
            @Override
            protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation,
                    VcChangeDescription description) throws IOException {
                if (failWrites.get()) throw new IOException("Storage unavailable");
                return super.commitSnapshot(operation, description);
            }
        };
        try (OrionKeyMaterial material = open(store)) {
            failWrites.set(true);
            assertThatThrownBy(
                            () -> {
                                try (KeyMaterialOperation modification =
                                        material.open(new VcRevision.Stored(material.revision()))) {
                                    modification.generateKey(
                                            ("unsaved"),
                                            Set.of((KeyMaterialPurpose.ACME_ACCOUNT)),
                                            KeyMaterialAlgorithm.RSA,
                                            3072);
                                    modification.commit(VcChangeDescription.EMPTY);
                                }
                            })
                    .isInstanceOf(java.io.UncheckedIOException.class);
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

    private OrionKeyMaterial open(InMemoryBlobStorage store) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    runtime.events));
        }
    }


}
