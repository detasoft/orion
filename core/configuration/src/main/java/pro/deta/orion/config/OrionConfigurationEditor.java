package pro.deta.orion.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.v2.OrionMaterialReference;
import pro.deta.orion.util.Result;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns configuration candidates and their single persistence boundary. */
@Singleton
public final class OrionConfigurationEditor {
    private final OrionVersionedConfigurationStorage storage;
    private final ConfigurationCipherCapability configurationCipher;
    private final ConfigurationMaterialCapability configurationMaterial;
    private final KeyMaterialScope materialScope;

    @Inject
    public OrionConfigurationEditor(OrionVersionedConfigurationStorage storage,
            BootstrapConfiguration configuration, ConfigurationCipherCapability configurationCipher,
            ConfigurationMaterialCapability configurationMaterial) {
        this.storage = storage;
        this.configurationCipher = configurationCipher;
        this.configurationMaterial = configurationMaterial;
        this.materialScope = KeyMaterialScope.cluster(configuration.getBootstrap().getKeyMaterial().getClusterId());
    }

    public OrionConfigurationModification edit(OrionRevision expectedRevision, ChangeMetadata metadata) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        OrionDocumentRevision snapshot = storage.load().valueOrFailure("Cannot load configuration for update");
        if (!snapshot.revision().equals(expectedRevision)) {
            throw new OrionConfigurationConcurrentUpdateException("Configuration revision changed", null);
        }
        return edit(snapshot, metadata);
    }

    public OrionConfigurationModification edit(OrionDocumentRevision snapshot, ChangeMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        OrionDocument document = document(snapshot).valueOrFailure("Cannot validate configuration for update");
        return new OrionConfigurationModification(new OrionDocumentRevision(document, snapshot.revision()),
                updated -> storage.save(snapshot.revision(), validateConfiguration(updated), metadata));
    }

    public Result<OrionDocument> document(OrionDocumentRevision snapshot) {
        try {
            OrionDocument document = validateConfiguration(snapshot.document());
            return new Result.Success<>(document);
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
                new KeyMaterialAlias(reference.alias()), purpose, KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(reference.version()), materialScope));
    }

    private void requireTrust(OrionMaterialReference reference) throws GeneralSecurityException {
        configurationMaterial.require(new TrustedCertificateDescriptor(new KeyMaterialAlias(reference.alias()),
                KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(reference.version()), materialScope));
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
