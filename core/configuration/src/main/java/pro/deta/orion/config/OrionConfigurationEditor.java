package pro.deta.orion.config;

import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.v2.OrionMaterialReference;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.Subscription;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Owns the last valid configuration, storage event processing, and edits against captured revisions. */
@Slf4j
@Singleton
public final class OrionConfigurationEditor implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    private final OrionVersionedConfigurationStorage storage;
    private final OrionEventManager events;
    private final ConfigurationSecretCipher configurationCipher;
    private final OrionKeyMaterial configurationMaterial;
    private volatile OrionDocumentRevision activeConfiguration;
    private volatile Subscription changeSubscription;

    @Inject
    public OrionConfigurationEditor(OrionVersionedConfigurationStorage storage,
            ConfigurationSecretCipher configurationCipher,
            OrionKeyMaterial configurationMaterial, OrionEventManager events) {
        this.storage = storage;
        this.events = Objects.requireNonNull(events, "events");
        this.configurationCipher = configurationCipher;
        this.configurationMaterial = configurationMaterial;
    }

    public OrionDocumentRevision load() {
        return storage.load();
    }

    @Override
    public synchronized void onStart() {
        if (changeSubscription != null) {
            throw new IllegalStateException("Configuration editor is already running");
        }
        changeSubscription = events.subscribe(ConfigurationChanged.class, this::onConfigurationChange);
        try {
            OrionDocumentRevision snapshot = load();
            if (snapshot.result() instanceof Result.Failure<OrionDocument> failure
                    && failure.code() == Result.FailureCode.NOT_FOUND) {
                activeConfiguration = null;
                return;
            }
            activate(snapshot);
        } catch (RuntimeException failure) {
            onStop();
            throw failure;
        }
    }

    @Override
    public synchronized void onStop() {
        if (changeSubscription != null) {
            changeSubscription.close();
            changeSubscription = null;
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return changeSubscription != null;
    }

    public boolean isInitialized() {
        return activeConfiguration != null;
    }

    public OrionDocumentRevision current() {
        OrionDocumentRevision snapshot = activeConfiguration;
        if (snapshot == null) {
            throw new IllegalStateException("Configuration is not initialized");
        }
        return snapshot;
    }

    public ConfigurationStatus status() {
        OrionDocumentRevision active = activeConfiguration;
        Optional<String> activeRevision = active == null ? Optional.empty() : revisionValue(active.revision());
        OrionDocumentRevision read = load();
        return switch (read.result()) {
            case Result.Success<OrionDocument> ignored -> new ConfigurationStatus(
                    revisionValue(read.revision()), activeRevision,
                    document(read) instanceof Result.Success<?> ? "valid" : "invalid");
            case Result.Failure<OrionDocument> ignored ->
                    new ConfigurationStatus(revisionValue(read.revision()), activeRevision, "unavailable");
        };
    }

    private static Optional<String> revisionValue(OrionRevision revision) {
        return switch (revision) {
            case OrionRevision.Absent ignored -> Optional.empty();
            case OrionRevision.Stored stored -> Optional.of(stored.value());
        };
    }

    public record ConfigurationStatus(
            Optional<String> storedRevision, Optional<String> activeRevision, String validation) {}

    private synchronized void onConfigurationChange(ConfigurationChanged event) {
        if (changeSubscription == null || event.storage() != storage) {
            return;
        }
        OrionDocumentRevision snapshot = event.snapshot();
        if (activeConfiguration != null && snapshot.revision().equals(activeConfiguration.revision())) {
            return;
        }
        try {
            activate(snapshot);
        } catch (RuntimeException failure) {
            log.warn("Retaining the last valid configuration after reload failure", failure);
        }
    }

    private void activate(OrionDocumentRevision snapshot) {
        document(snapshot).failOnFailure("Invalid configuration");
        activeConfiguration = snapshot;
    }

    public OrionConfigurationModification edit(
            OrionRevision expectedRevision, ChangeMetadata metadata) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        OrionDocumentRevision snapshot = load();
        snapshot.result().failOnFailure("Cannot load configuration for update");
        if (!snapshot.revision().equals(expectedRevision)) {
            throw new OrionConfigurationConcurrentUpdateException("Configuration revision changed", null);
        }
        return edit(snapshot, metadata);
    }

    public OrionConfigurationModification edit(OrionDocumentRevision snapshot, ChangeMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        OrionDocument document = document(snapshot).valueOrFailure("Cannot validate configuration for update");
        return new OrionConfigurationModification(
                document, snapshot.revision(),
                updated -> storage.save(snapshot.revision(), validateConfiguration(updated), metadata));
    }

    private Result<OrionDocument> document(OrionDocumentRevision snapshot) {
        if (snapshot.result() instanceof Result.Failure<OrionDocument> failure) {
            return failure;
        }
        try {
            validateConfiguration(snapshot.result()
                    .valueOrFailure("Cannot read configuration document"));
            return snapshot.result();
        } catch (RuntimeException failure) {
            return new Result.Failure<>(Result.FailureCode.GENERAL,
                    "Cannot validate configuration file", failure);
        }
    }

    private OrionDocument validateConfiguration(OrionDocument document) {
        try {
            if (document.system().https().isPresent()) {
                OrionHttpsConfiguration https = document.system().https().orElseThrow();
                if (https.identity().isPresent()) {
                    requireKey(https.identity().orElseThrow(), KeyMaterialPurpose.TLS_IDENTITY);
                }
                if (https.serverIssuerTrustAnchor().isPresent()) {
                    requireTrust(https.serverIssuerTrustAnchor().orElseThrow());
                }
                for (OrionMaterialReference anchor : https.clientTrustAnchors()) {
                    requireTrust(anchor);
                }
                if (https.acme().isPresent() && https.acme().orElseThrow().accountMaterial().isPresent()) {
                    requireKey(https.acme().orElseThrow().accountMaterial().orElseThrow(),
                            KeyMaterialPurpose.ACME_ACCOUNT);
                }
            }
            new ConfigurationSecrets(() -> document, configurationCipher).validate(document);
            return document;
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(
                    "Configuration material reference is unavailable or invalid", error);
        }
    }

    private void requireKey(OrionMaterialReference reference, KeyMaterialPurpose purpose)
            throws GeneralSecurityException {
        configurationMaterial.require(new KeyMaterialDescriptor(
                reference.alias(), Set.of(purpose), KeyMaterialAlgorithm.RSA,
                reference.version()));
    }

    private void requireTrust(OrionMaterialReference reference) throws GeneralSecurityException {
        configurationMaterial.require(new TrustedCertificateDescriptor(reference.alias(), Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                KeyMaterialAlgorithm.RSA, reference.version()));
    }

    public void withAccess(ChangeMetadata metadata, OrionRevision expectedRevision,
            Consumer<OrionConfigurationModification> operation) {
        OrionConfigurationModification modification = edit(expectedRevision, metadata);
        try {
            operation.accept(modification);
        } finally {
            modification.discard();
        }
    }
}
