package pro.deta.orion.keymaterial;

import pro.deta.orion.event.OrionEventManager;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PKCS12Attribute;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void readsCommittedMaterialWhileAnotherKeyIsBeingPrepared() throws Exception {
        InMemoryKeyMaterialContentSource source = new InMemoryKeyMaterialContentSource();
        KeyMaterialDescriptor signing = descriptor("existing", KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA);
        KeyMaterialDescriptor cipher = descriptor("cipher", KeyMaterialPurpose.CONFIGURATION_CIPHER,
                KeyMaterialAlgorithm.AES);
        KeyMaterialDescriptor trust = descriptor("root", KeyMaterialPurpose.TRUST_ANCHOR,
                KeyMaterialAlgorithm.RSA);
        X509Certificate root = TestCertificateChain.root("Read concurrency root").certificate();
        KeyPair pair;
        try (KeyMaterialOptions options = options();
             KeyMaterialService seed = new KeyMaterialService(new KeyMaterialContentStore(
                     source, new KeyMaterialCodec(options), new OrionEventManager()))) {
            KeyMaterialModification modification = seed.modify(seed.revision());
            modification.generateKey(signing, 2048);
            modification.generateKey(cipher, 256);
            modification.setTrustedCertificate(trust, root);
            modification.apply();
            pair = seed.read(signing.id()).keyPair();
        }

        CountDownLatch preparationStarted = new CountDownLatch(1);
        CountDownLatch releasePreparation = new CountDownLatch(1);
        KeyMaterialStorageCertificateFactory factory = new KeyMaterialStorageCertificateFactory() {
            @Override
            public X509Certificate create(String alias, String purpose, KeyPair keyPair)
                    throws GeneralSecurityException {
                preparationStarted.countDown();
                try {
                    if (!releasePreparation.await(10, TimeUnit.SECONDS)) {
                        throw new GeneralSecurityException("Preparation was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new GeneralSecurityException("Preparation interrupted", interrupted);
                }
                return super.create(alias, purpose, keyPair);
            }
        };
        try (KeyMaterialOptions options = options();
             KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                     source, new KeyMaterialCodec(options), new OrionEventManager()), factory);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            KeyMaterialDescriptor prepared = descriptor("prepared", KeyMaterialPurpose.SERVER_SIGNING,
                    KeyMaterialAlgorithm.RSA);
            Future<?> preparation = executor.submit(() -> {
                KeyMaterialModification modification = service.modify(service.revision());
                try {
                    modification.importKey(prepared, pair);
                    modification.apply();
                } finally {
                    modification.discard();
                }
                return null;
            });
            try {
                assertThat(preparationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> reads = executor.submit(() -> {
                    assertThat(service.revision()).isEqualTo("1");
                    assertThat(service.hasDurableSnapshot()).isTrue();
                    assertThat(service.containsAlias(signing.alias(), signing.purposes())).isTrue();
                    assertThat(service.read(signing.id()).keyPair().getPublic()).isEqualTo(pair.getPublic());
                    assertThat(service.read(signing.id()).keyPair().getPrivate().getEncoded())
                            .isEqualTo(pair.getPrivate().getEncoded());
                    assertThat(service.read(signing.id()).certificateChain()
                            .toArray(Certificate[]::new)).hasSize(1);
                    assertThat(service.read(trust.id()).trustedCertificate()).isEqualTo(root);
                    service.validateExisting(signing);

                    assertThat(((KeyStore.SecretKeyEntry) service.read(cipher.id()).content())
                            .getSecretKey().getAlgorithm()).isEqualTo("AES");
                    assertThat(service.readEntry(signing.alias()).alias()).isEqualTo(signing.alias());
                    assertThat(service.inventory()).extracting(KeyMaterialId::alias)
                            .containsExactlyInAnyOrder(signing.alias(), cipher.alias(), trust.alias());
                    assertThat(service.containsAlias(prepared.alias(), Set.of())).isFalse();
                    return null;
                });
                reads.get(3, TimeUnit.SECONDS);
            } finally {
                releasePreparation.countDown();
            }
            preparation.get(5, TimeUnit.SECONDS);
            assertThat(service.revision()).isEqualTo("2");
            assertThat(service.containsAlias(prepared.alias(), Set.of())).isTrue();
        }
    }

    @Test
    void createsPkcs12InMemoryStoreAndReloadsPrivateKey() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();

        KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
        KeyPair generated = generateKey(service, descriptor(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);
        String savedVersion = store.load().revision();

        KeyMaterialService reloaded = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
        KeyPair loaded =
                reloaded.read(new KeyMaterialId(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                1)).keyPair();
        Certificate[] chain =
                reloaded.read(new KeyMaterialId(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                1)).certificateChain().toArray(Certificate[]::new);

        assertThat(savedVersion).isNotBlank();
        assertThat(reloaded.containsAlias(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS, Set.of())).isTrue();
        assertThat(loaded.getPublic().getEncoded()).isEqualTo(generated.getPublic().getEncoded());
        assertThat(loaded.getPrivate().getAlgorithm()).isEqualTo(generated.getPrivate().getAlgorithm());
        assertThat(chain).hasSize(1);
        assertThat(chain[0]).isInstanceOf(X509Certificate.class);

        X509Certificate storageCertificate = (X509Certificate) chain[0];
        assertThat(storageCertificate.getBasicConstraints()).isEqualTo(-1);
        assertThat(storageCertificate.getSubjectX500Principal().getName())
                .contains(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS);
        storageCertificate.verify(loaded.getPublic());
    }

    @Test
    void localPkcs12StoreCanBeOpenedWithJavaKeyStore() throws Exception {
        Path keyStorePath = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(keyStorePath);

        KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
        generateKey(service, descriptor(
                KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS,
                KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA), 2048);

        KeyStore keyStore = KeyStore.getInstance(KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE);
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, KeyMaterialTestConstants.password());
        }

        assertThat(Files.size(keyStorePath)).isGreaterThan(0);
        assertThat(keyStore.containsAlias(KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS)).isTrue();
        assertThat(keyStore.getCertificateChain(
                KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS)).hasSize(1);
    }

    @Test
    void trustedCertificatesRoundTripSeparatelyFromPrivateKeyEntries() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
        generateKey(service, descriptor(
                KeyMaterialTestConstants.ORION_CA_2026_05_ALIAS,
                KeyMaterialPurpose.CERTIFICATE_AUTHORITY, KeyMaterialAlgorithm.RSA), 2048);
        X509Certificate storageCertificate =
                (X509Certificate) service
                .read(new KeyMaterialId(KeyMaterialTestConstants.ORION_CA_2026_05_ALIAS,
                1)).certificateChain().toArray(Certificate[]::new)[0];
        X509Certificate issuerCertificate = TestCertificateChain.root("Public Root").certificate();
        KeyMaterialDescriptor descriptor = trustedCertificate(
                KeyMaterialTestConstants.ORION_CA_CERT_2026_05_ALIAS,
                1);

        assertThatThrownBy(() -> trustCertificate(service, descriptor, storageCertificate))
                .hasMessageContaining("certificate authority");
        trustCertificate(service, descriptor, issuerCertificate);

        KeyMaterialService reloaded = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
        X509Certificate trustedCertificate = reloaded.read(descriptor.id()).trustedCertificate();

        assertThat(trustedCertificate.getEncoded()).isEqualTo(issuerCertificate.getEncoded());
        assertThatThrownBy(() -> reloaded
                .read(new KeyMaterialId(KeyMaterialTestConstants.ORION_CA_CERT_2026_05_ALIAS,
                1)).keyPair().getPrivate())
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Private key not found");
    }

    @Test
    void validatesTypedTrustedCertificateMetadataAndEntryType() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        KeyMaterialDescriptor original = trustedCertificate(
                "public-root-v1", 1);
        KeyMaterialDescriptor privateEntry = new KeyMaterialDescriptor(
                "private-entry-v1",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                KeyMaterialAlgorithm.RSA,
                1);
        TestCertificateChain.Authority root = TestCertificateChain.root("Public Root");
        try (KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            trustCertificate(service, original, root.certificate());
            generateKey(service, privateEntry, 2048);
        }

        try (KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            KeyMaterialDescriptor wrongVersion = trustedCertificate(
                    original.alias(), 2);
            KeyMaterialDescriptor wrongAlgorithm = new KeyMaterialDescriptor(
                    original.alias(), Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                    KeyMaterialAlgorithm.EC,
                    original.version());
            KeyMaterialDescriptor wrongEntryType = trustedCertificate(
                    privateEntry.alias(), 1);

            assertThatThrownBy(() -> service.validateExisting(wrongVersion))
                    .hasMessageContaining("version");
            assertThatThrownBy(() -> service.validateExisting(wrongAlgorithm))
                    .hasMessageContaining("algorithm");
            assertThatThrownBy(() -> service.validateExisting(wrongEntryType))
                    .hasMessageContaining("entry type");
        }
    }

    @Test
    void discoversTypedPrivateKeysByPurpose() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();

        KeyMaterialDescriptor rsa = descriptor(
                "ssh-host-z-rsa-v1", KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA);
        KeyMaterialDescriptor ec = descriptor(
                "ssh-host-a-ec-v1", KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.EC);
        KeyMaterialDescriptor signing = descriptor(
                "server-signing-v1",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA);
        try (KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            generateKey(service, rsa, 2048);
            generateKey(service, ec, 256);
            generateKey(service, signing, 2048);
        }

        try (KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {

        }
    }

    @Test
    void inventoriesTypedMaterialIdsAndReadsTheirContent() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();

        KeyMaterialDescriptor identity = descriptor(
                "tls-identity-v1", KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA);
        KeyMaterialDescriptor cipher = descriptor(
                "configuration-v1", KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES);
        KeyMaterialDescriptor trust = trustedCertificate("issuer-v1", 1);
        TestCertificateChain.Authority issuer = TestCertificateChain.root("Public Root");
        try (KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            KeyPair keyPair = generateKey(service, identity, 2048);
            KeyMaterialModification modification = service.modify(service.revision());
            try {
                modification.generateKey(cipher, 256);
                modification.setTrustedCertificate(trust, issuer.certificate());
                modification.installCertificateChain(identity,
                        List.of(TestCertificateChain.leaf("orion.test", keyPair, issuer)), Optional.of(trust));
                modification.apply();
            } finally {
                modification.discard();
            }
        }

        try (KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()))) {
            List<KeyMaterialId> entries = service.inventory();
            assertThat(entries).extracting(KeyMaterialId::alias)
                    .containsExactly("configuration-v1", "issuer-v1", "tls-identity-v1");
            assertThat(service.read(entries.get(0)).content()).isInstanceOf(KeyStore.SecretKeyEntry.class);
            assertThat(service.read(entries.get(1)).content())
                    .isInstanceOf(KeyStore.TrustedCertificateEntry.class);
            assertThat(service.read(entries.get(2)).certificateChain()).hasSize(1);
            assertThat(service.read(entries.get(2)).certificateChain().getFirst()
                    .getSubjectX500Principal().getName())
                    .contains("orion.test");

        }
    }

    @Test
    void inventoryRejectsAnAlgorithmThatDoesNotMatchTheKey() throws Exception {
        TestCertificateChain.Authority issuer = TestCertificateChain.root("Mismatched metadata");
        KeyStore.PrivateKeyEntry entry = new KeyStore.PrivateKeyEntry(
                issuer.keyPair().getPrivate(), new Certificate[]{issuer.certificate()},
                Set.of(new PKCS12Attribute("2.25.260349814076539099691640886169327825428", "SSH_HOST;EC")));
        KeyMaterialContentStore store = new KeyMaterialContentStore(
                new InMemoryKeyMaterialContentSource(), new KeyMaterialCodec(options()), new OrionEventManager());
        try (KeyMaterialService service = new KeyMaterialService(store)) {
            store.apply(store.revision(), List.of(
                    new KeyMaterialContentStore.Entry("mismatched", entry, 1, false)));

            assertThatThrownBy(() -> service.inventory())
                    .isInstanceOf(GeneralSecurityException.class)
                    .hasMessageContaining("algorithm does not match");

        }
    }

    @Test
    void refusesPrivateKeyWhenCertificateChainHasDifferentPublicKey() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));

        KeyPair signingKey = generateKey(service, descriptor(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);
        generateKey(service, descriptor(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);
        Certificate[] mismatchedChain =
                service.read(new KeyMaterialId(KeyMaterialTestConstants.SERVER_SIGNING_2026_06_ALIAS,
                1)).certificateChain().toArray(Certificate[]::new);
        KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(
                KeyMaterialTestConstants.BAD_SERVER_SIGNING_ALIAS,
                Set.of(KeyMaterialPurpose.TLS_IDENTITY),
                KeyMaterialAlgorithm.RSA,
                1);

        KeyMaterialModification modification = service.modify(service.revision());
        try {
            modification.importKey(descriptor, signingKey);
            assertThatThrownBy(() -> modification.installCertificateChain(
                    descriptor, List.of(mismatchedChain), Optional.empty()))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("public key does not match");
        } finally {
            modification.discard();
        }
    }

    @Test
    void detectsConflictingStoreUpdates() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();

        KeyMaterialService initial = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
        generateKey(initial, descriptor(
                KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);

        KeyMaterialService first = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
        KeyMaterialService second = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));

        generateKey(second, descriptor(
                KeyMaterialTestConstants.SSH_HOST_RSA_2026_05_ALIAS,
                KeyMaterialPurpose.SSH_HOST, KeyMaterialAlgorithm.RSA), 2048);

        KeyMaterialModification modification = first.modify(first.revision());
        try {
            modification.generateKey(descriptor("acme-account",
                    KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA), 2048);
            assertThatThrownBy(modification::apply)
                    .isInstanceOf(KeyMaterialStoreConflictException.class)
                    .hasMessageContaining("changed before save");
        } finally {
            modification.discard();
        }
    }

    @Test
    void loadsPkcs12BytesFromReadOnlyStore() throws Exception {
        InMemoryKeyMaterialContentSource writableStore = new InMemoryKeyMaterialContentSource();
        KeyMaterialService writable = new KeyMaterialService(new KeyMaterialContentStore(
                writableStore,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));
        generateKey(writable, descriptor(
                KeyMaterialTestConstants.HTTPS_2026_05_ALIAS,
                KeyMaterialPurpose.TLS_IDENTITY, KeyMaterialAlgorithm.RSA), 2048);

        byte[] pkcs12Bytes = writableStore.load().bytes();
        KeyMaterialContentSource readOnlyStore = new ReadOnlyKeyMaterialContentSource(pkcs12Bytes, "test material");
        KeyMaterialService readOnly = new KeyMaterialService(new KeyMaterialContentStore(
                readOnlyStore,
                new KeyMaterialCodec(options()),
                new OrionEventManager()));

        assertThat(readOnly.containsAlias(KeyMaterialTestConstants.HTTPS_2026_05_ALIAS, Set.of())).isTrue();
        assertThat(readOnly.read(new KeyMaterialId(KeyMaterialTestConstants.HTTPS_2026_05_ALIAS,
                1)).certificateChain().toArray(Certificate[]::new)).hasSize(1);
    }

    @Test
    void serviceOwnsAndClearsItsPasswordLifecycle() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();
        KeyMaterialOptions callerOptions = options();
        KeyMaterialService service = new KeyMaterialService(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(callerOptions),
                new OrionEventManager()));

        try {
            callerOptions.close();
            generateKey(service, descriptor(
                    KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS,
                    KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA), 2048);

            service.close();

            assertThatThrownBy(callerOptions::password)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThatThrownBy(() -> service.containsAlias(
                    KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS, Set.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        } finally {
            service.close();
            callerOptions.close();
        }
    }

    @Test
    void corruptExistingStoreIsNeverReplaced() throws Exception {
        Path keyStorePath = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(keyStorePath);
        byte[] corruptBytes = "not-a-pkcs12-store".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String corruptVersion = store.write(corruptBytes, null);

        try (KeyMaterialOptions options = options()) {
            assertThatThrownBy(() -> new KeyMaterialService(new KeyMaterialContentStore(
                    store,
                    new KeyMaterialCodec(options),
                    new OrionEventManager())))
                    .isInstanceOf(java.io.IOException.class);
        }

        String afterFailureRevision = store.load().revision();

        byte[] afterFailureBytes = store.read(afterFailureRevision);
        assertThat(afterFailureRevision).isEqualTo(corruptVersion);
        assertThat(afterFailureBytes).isEqualTo(corruptBytes);
    }

    private static KeyPair generateKey(KeyMaterialService service, KeyMaterialDescriptor descriptor,
            int keySize) throws Exception {
        KeyMaterialModification modification = service.modify(service.revision());
        try {
            modification.generateKey(descriptor, keySize);
            modification.apply();
            return service.read(descriptor.id()).keyPair();
        } finally {
            modification.discard();
        }
    }

    private static void trustCertificate(KeyMaterialService service, KeyMaterialDescriptor descriptor,
            X509Certificate certificate) throws Exception {
        KeyMaterialModification modification = service.modify(service.revision());
        try {
            modification.setTrustedCertificate(descriptor, certificate);
            modification.apply();
        } finally {
            modification.discard();
        }
    }

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
    }

    private static KeyMaterialDescriptor trustedCertificate(
            String alias,
            long version) {
        return new KeyMaterialDescriptor(
                alias, Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                KeyMaterialAlgorithm.RSA,
                version);
    }

    private static KeyMaterialDescriptor descriptor(
            String alias,
            KeyMaterialPurpose purpose,
            KeyMaterialAlgorithm algorithm) {
        return new KeyMaterialDescriptor(
                alias,
                Set.of(purpose),
                algorithm,
                1);
    }
}
