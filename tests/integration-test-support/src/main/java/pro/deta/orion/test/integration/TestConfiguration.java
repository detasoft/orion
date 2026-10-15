package pro.deta.orion.test.integration;

import java.util.Optional;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.RootPasswordReset;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.config.ConfigurationChanged;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.keymaterial.ConfigurationSecretCipher;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialCapabilities;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialService;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.SigningIdentity;
import pro.deta.orion.keymaterial.TrustedCertificateDescriptor;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.test.util.TestEvents;
import pro.deta.orion.util.Result;

/** Provides a real ACL and editor backed by versioned documents in memory. */
public final class TestConfiguration implements AutoCloseable {
    private final TestEvents events;
    private final MemoryStorage storage = new MemoryStorage();
    private final OrionConfigurationEditor editor;
    private final OrionAccessControlServiceImpl acl;
    private final ConfigurationSecretCipher cipher;

    public TestConfiguration(TestEvents events) {
        this(events, new BootstrapConfiguration(), defaultCipher(events),
                new OrionKeyMaterial() {
                    @Override
                    public void require(KeyMaterialDescriptor descriptor) {}

                    @Override
                    public void require(TrustedCertificateDescriptor descriptor) {}
                }, SigningIdentity.unavailable());
    }

    public TestConfiguration(TestEvents events, BootstrapConfiguration bootstrap,
            ConfigurationSecretCipher cipher, OrionKeyMaterial material,
            SigningIdentity identity) {
        this.events = events;
        this.cipher = cipher;
        editor = new OrionConfigurationEditor(storage, bootstrap, cipher, material, events.manager());
        acl = new OrionAccessControlServiceImpl(new OrionPasswordHashingService(), new RootPasswordReset(false),
                identity, bootstrap, editor);
        events.own(this);
        acl.onStart();
    }

    public OrionAccessControlServiceImpl acl() {
        return acl;
    }

    public OrionConfigurationEditor editor() {
        return editor;
    }

    public OrionVersionedConfigurationStorage storage() {
        return storage;
    }

    public ConfigurationSecrets secrets() {
        return new ConfigurationSecrets(() -> acl.configuration().result()
                .valueOrFailure("Cannot read configuration document"), cipher);
    }

    private static ConfigurationSecretCipher defaultCipher(TestEvents events) {
        try {
            KeyMaterialService material = KeyMaterialService.open(new InMemoryKeyMaterialContentStore(),
                    KeyMaterialOptions.pkcs12("test-password".toCharArray()));
            events.own(material);
            KeyMaterialDescriptor descriptor =
                    new KeyMaterialDescriptor(
                            new KeyMaterialAlias("configuration-v1"),
                            KeyMaterialPurpose.CONFIGURATION_CIPHER,
                            KeyMaterialAlgorithm.AES,
                            new KeyMaterialVersion(1),
                            KeyMaterialScope.cluster(
                                    new BootstrapConfiguration()
                                            .getBootstrap()
                                            .getKeyMaterial()
                                            .getClusterId()));
            material.generateSecretKeyIfMissing(descriptor, 256);
            return KeyMaterialCapabilities.open(material, java.util.List.of(descriptor))
                    .configurationCipher(descriptor);
        } catch (java.io.IOException | java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot initialize test configuration cipher", failure);
        }
    }

    public OrionDocumentRevision stored() {
        return storage.snapshot;
    }

    public void publish(OrionDocument document, Optional<String> revision) {
        OrionRevision selected = revision.<OrionRevision>map(OrionRevision.Stored::new)
                .orElseGet(() -> new OrionRevision.Stored("published-" + ++storage.revision));
        storage.snapshot = new OrionDocumentRevision(Result.of(document), selected);
        events.manager().publishAndWait(new ConfigurationChanged(storage, storage.snapshot));
    }

    @Override
    public void close() {
        acl.onStop();
    }

    private final class MemoryStorage implements OrionVersionedConfigurationStorage {
        private volatile OrionDocumentRevision snapshot = new OrionDocumentRevision(
                Result.of(OrionDocument.withAccessControl(new AccessControl())),
                new OrionRevision.Stored("initial"));
        private int revision;

        @Override
        public OrionDocumentRevision load() {
            return snapshot;
        }

        @Override
        public OrionDocumentRevision load(OrionRevision.Stored selected) {
            return snapshot.revision().equals(selected)
                    ? load()
                    : new OrionDocumentRevision(
                            new Result.Failure<>(Result.FailureCode.NOT_FOUND),
                            new OrionRevision.Absent());
        }

        @Override
        public synchronized OrionRevision.Stored save(
                OrionRevision expected, OrionDocument document, ChangeMetadata metadata) {
            if (!snapshot.revision().equals(expected)) {
                throw new OrionConfigurationConcurrentUpdateException("Configuration changed", null);
            }
            if (snapshot.revision() instanceof OrionRevision.Stored stored && snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .equals(document)) return stored;
            OrionRevision.Stored saved = new OrionRevision.Stored("saved-" + ++revision);
            snapshot = new OrionDocumentRevision(Result.of(document), saved);
            events.manager().publish(new ConfigurationChanged(this, snapshot));
            return saved;
        }
    }
}
