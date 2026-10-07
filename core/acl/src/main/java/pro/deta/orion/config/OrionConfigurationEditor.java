package pro.deta.orion.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Data;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.internal.UserEmailMessage;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.*;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.v2.OrionMaterialReference;
import pro.deta.orion.util.Result;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.function.Function;

/** Owns configuration candidates and their single persistence boundary. */
@Singleton
public final class OrionConfigurationEditor {
    private final OrionConfigurationStorage storage;
    private final ConfigurationCipherCapability configurationCipher;
    private final ConfigurationMaterialCapability configurationMaterial;
    private final KeyMaterialScope materialScope;

    @Inject
    public OrionConfigurationEditor(OrionConfigurationStorage storage,
            BootstrapConfiguration configuration, ConfigurationCipherCapability configurationCipher,
            ConfigurationMaterialCapability configurationMaterial) {
        this.storage = storage;
        this.configurationCipher = configurationCipher;
        this.configurationMaterial = configurationMaterial;
        this.materialScope = KeyMaterialScope.cluster(configuration.getBootstrap().getKeyMaterial().getClusterId());
    }

    public synchronized OrionConfigurationModification edit(Optional<String> expectedRevision, UserEmailMessage author) {
        if (expectedRevision.isPresent()) {
            if (expectedRevision.get().isBlank()) {
                throw new IllegalArgumentException("Configuration revision is required");
            }
        }
        OrionDocumentRevision snapshot = storage.load().valueOrFailure("Cannot load configuration for update");
        if (expectedRevision.isPresent()) {
            if (!snapshot.revision().equals(expectedRevision)) {
                throw new OrionConfigurationConcurrentUpdateException("Configuration revision changed", null);
            }
        }
        OrionDocument document = document(snapshot).valueOrFailure("Cannot validate configuration for update");
        return new OrionConfigurationModification(document, new ModifiedOnSave(snapshot, author));
    }

    public synchronized void onStorageChange() {
        ("configuration repository change");
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

    void withAccess(UserEmailMessage userEmail, String expectedRevision, Consumer<OrionConfigurationModification> operation) throws IOException {
        if (expectedRevision == null || expectedRevision.isBlank()) {
            throw new IllegalArgumentException("Configuration revision is required");
        }
        OrionDocumentRevision snapshot = storage.load().valueOrFailure("Cannot load configuration for update");
        if (!snapshot.revision().equals(Optional.of(expectedRevision))) {
            throw new OrionConfigurationConcurrentUpdateException("Configuration revision changed", null);
        }

        OrionDocument document = document(snapshot).valueOrFailure("Cannot validate configuration for update");
        OrionConfigurationModification orionConfigurationModification = new OrionConfigurationModification(document, new ModifiedOnSave(snapshot, userEmail));

        try {
            operation.accept(orionConfigurationModification);
        } finally {
            orionConfigurationModification.discard();
        }
    }

    @Data
    private class ModifiedOnSave implements Consumer<OrionDocument> {
        private final OrionDocumentRevision snapshot;
        private final UserEmailMessage author;

        @Override
        public void accept(OrionDocument orionDocument) {
            storage.save(snapshot, orionDocument, author);
        }
    }
}
