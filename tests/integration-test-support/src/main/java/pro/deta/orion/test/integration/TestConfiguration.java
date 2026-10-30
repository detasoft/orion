package pro.deta.orion.test.integration;

import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialRepository;

import java.util.Set;

import java.util.Optional;
import java.util.List;
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
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.ConfigurationSecretContext;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelope;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.test.util.OrionTestRuntime;
import pro.deta.orion.util.Result;

/** Provides a real ACL and editor backed by versioned documents in memory. */
public final class TestConfiguration implements AutoCloseable {
    private final OrionTestRuntime events;
    private final MemoryStorage storage = new MemoryStorage();
    private final OrionConfigurationEditor editor;
    private final OrionAccessControlServiceImpl acl;
    private final OrionKeyMaterial material;

    public TestConfiguration(OrionTestRuntime events) {
        this(events, new BootstrapConfiguration(), defaultMaterial(events));
    }

    public TestConfiguration(OrionTestRuntime events, BootstrapConfiguration bootstrap, OrionKeyMaterial material) {
        this.events = events;
        this.material = material;
        editor = new OrionConfigurationEditor(storage, material, events.events());
        SigningMaterialSet signing = new SigningMaterialSet(
                new KeyMaterialId("signing", 1), List.of());
        acl = new OrionAccessControlServiceImpl(new OrionPasswordHashingService(), new RootPasswordReset(false),
                material, signing, bootstrap, editor);
        events.own(this);
        editor.onStart();
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
        return new ConfigurationSecrets(() -> editor.current().result()
                .valueOrFailure("Cannot read configuration document"), material);
    }

    private static OrionKeyMaterial defaultMaterial(OrionTestRuntime events) {
        try {
            OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                    new InMemoryBlobStorage(),
                    new KeyMaterialCodec(KeyMaterialOptions.pkcs12("test-password".toCharArray())),
                    events.unstartedEvents()));
            events.own(material);
            return new OrionKeyMaterial() {
                @Override
                public void require(KeyMaterialId descriptor, Set<KeyMaterialPurpose> purposes, KeyMaterialAlgorithm algorithm) {}

                @Override
                public ConfigurationSecretEnvelope seal(
                        KeyMaterialId descriptor, byte[] plaintext,
                        ConfigurationSecretContext context)
                        throws java.security.GeneralSecurityException {
                    return material.seal(descriptor, plaintext, context);
                }

                @Override
                public byte[] open(KeyMaterialId descriptor,
                        ConfigurationSecretEnvelope envelope,
                        ConfigurationSecretContext context)
                        throws java.security.GeneralSecurityException {
                    return material.open(descriptor, envelope, context);
                }
            };
        } catch (java.io.IOException | java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot initialize test key material", failure);
        }
    }

    public OrionDocumentRevision stored() {
        return storage.snapshot;
    }

    public void publish(OrionDocument document, Optional<String> revision) {
        OrionRevision selected = revision.<OrionRevision>map(OrionRevision.Stored::new)
                .orElseGet(() -> new OrionRevision.Stored("published-" + ++storage.revision));
        storage.snapshot = new OrionDocumentRevision(Result.of(document), selected);
        events.events().publishAndWait(new ConfigurationChanged(storage, storage.snapshot));
    }

    @Override
    public void close() {
        acl.onStop();
        editor.onStop();
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
            events.events().publish(new ConfigurationChanged(this, snapshot));
            return saved;
        }
    }
}
