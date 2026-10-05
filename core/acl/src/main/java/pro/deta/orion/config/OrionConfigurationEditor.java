package pro.deta.orion.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.internal.UserEmail;
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
import java.util.function.UnaryOperator;

/** Owns configuration candidates and their single persistence boundary. */
@Singleton
public final class OrionConfigurationEditor {
    private final OrionConfigurationStorage storage;
    private final OrionDesiredState desiredState;
    private final ConfigurationCipherCapability configurationCipher;
    private final ConfigurationMaterialCapability configurationMaterial;
    private final KeyMaterialScope materialScope;
    private final CopyOnWriteArrayList<Preparation> preparations =
            new CopyOnWriteArrayList<>();
    private boolean saving;

    @Inject
    public OrionConfigurationEditor(OrionConfigurationStorage storage,
            BootstrapConfiguration configuration, ConfigurationCipherCapability configurationCipher,
            ConfigurationMaterialCapability configurationMaterial, OrionDesiredState desiredState) {
        this.storage = storage;
        this.desiredState = desiredState;
        this.configurationCipher = configurationCipher;
        this.configurationMaterial = configurationMaterial;
        this.materialScope = KeyMaterialScope.cluster(configuration.getBootstrap().getKeyMaterial().getClusterId());
    }

    public synchronized OrionConfigurationEdit edit() {
        return edit(storage.load().valueOrFailure("Cannot load configuration for update"));
    }

    public synchronized OrionConfigurationEdit edit(String expectedRevision) {
        if (expectedRevision == null || expectedRevision.isBlank()) {
            throw new IllegalArgumentException("Configuration revision is required");
        }
        ConfigurationFile snapshot = storage.load().valueOrFailure("Cannot load configuration for update");
        if (!snapshot.revision().equals(Optional.of(expectedRevision))) {
            throw new OrionConfigurationConcurrentUpdateException("Configuration revision changed", null);
        }
        return edit(snapshot);
    }

    public OrionConfigurationEdit edit(ConfigurationFile snapshot) {
        return new Edit(snapshot, document(snapshot).valueOrFailure("Cannot validate configuration for update"));
    }

    public OrionConfigurationStorage.ChangeSubscription onPrepare(String message,
            UnaryOperator<ConfigurationFile> operation) {
        Preparation preparation = new Preparation(Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(operation, "preparation"));
        preparations.add(preparation);
        return () -> preparations.remove(preparation);
    }

    public synchronized void onStorageChange() {
        if (!saving) reload("configuration repository change");
    }

    public synchronized OrionDesiredState.Snapshot reload(String initiator) {
        return reload(storage.load().valueOrFailure("Cannot reload configuration after " + initiator));
    }

    public synchronized OrionDesiredState.Snapshot reload(ConfigurationFile snapshot) {
        while (true) {
            document(snapshot).valueOrFailure("Invalid configuration for reload");
            boolean retry = false;
            for (Preparation preparation : preparations) {
                ConfigurationFile prepared = Objects.requireNonNull(preparation.operation().apply(snapshot),
                        "prepared configuration");
                if (Arrays.equals(snapshot.content(), prepared.content())) continue;
                document(prepared).valueOrFailure("Invalid prepared configuration");
                try {
                    save(prepared, preparation.message(), UserEmail.EMPTY);
                } catch (OrionConfigurationConcurrentUpdateException conflict) {
                    snapshot = storage.load().valueOrFailure("Cannot reload configuration after concurrent update");
                    retry = true;
                    break;
                }
                snapshot = storage.load().valueOrFailure("Cannot reload prepared configuration");
                retry = true;
                break;
            }
            if (!retry) break;
        }
        OrionDocument document = document(snapshot).valueOrFailure("Invalid prepared configuration");
        if (desiredState.isPublished()) {
            OrionDesiredState.Snapshot current = desiredState.current();
            if (current.document().system().accessControl().equals(document.system().accessControl())) {
                document = document.replaceAccessControl(current.document().system().accessControl());
            }
            if (current.revision().equals(snapshot.revision()) && current.document().equals(document)) {
                return current;
            }
        }
        desiredState.publish(document, snapshot.revision());
        return desiredState.current();
    }

    private void save(ConfigurationFile file, String message, UserEmail author) {
        saving = true;
        try {
            storage.save(file, message, author);
        } finally {
            saving = false;
        }
    }

    public Result<OrionDocument> document(ConfigurationFile snapshot) {
        byte[] content = snapshot.content();
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            OrionDocument document = OrionXml.read(input);
            validateConfiguration(document);
            return new Result.Success<>(document);
        } catch (IOException | RuntimeException failure) {
            return new Result.Failure<>(Result.FailureCode.GENERAL,
                    "Cannot validate configuration file", failure);
        }
    }

    private void validateConfiguration(OrionDocument document) {
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

    /** The configuration was saved, but its synchronous activation failed. */
    public static final class ActivationFailedException extends IllegalStateException {
        private ActivationFailedException(RuntimeException cause) {
            super("Configuration was saved but could not be activated", cause);
        }
    }

    private final class Edit implements OrionConfigurationEdit {
        private final ConfigurationFile original;
        private OrionDocument candidate;
        private boolean closed;

        private Edit(ConfigurationFile original, OrionDocument document) {
            this.original = original;
            this.candidate = document;
        }

        @Override
        public OrionDocument document() {
            requireOpen();
            return candidate;
        }

        @Override
        public OrionConfigurationEdit update(UnaryOperator<OrionDocument> update) {
            requireOpen();
            candidate = Objects.requireNonNull(update.apply(candidate), "updated configuration");
            return this;
        }

        @Override
        public OrionDesiredState.Snapshot apply(String message, UserEmail author) {
            synchronized (OrionConfigurationEditor.this) {
                requireOpen();
                byte[] content;
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    OrionXml.write(candidate, output);
                    content = output.toByteArray();
                } catch (IOException failure) {
                    throw new IllegalStateException("Cannot serialize configuration", failure);
                }
                ConfigurationFile updated = new ConfigurationFile(content, original.revision());
                OrionConfigurationEditor.this.document(updated).valueOrFailure("Invalid updated configuration");
                closed = true;
                message = Objects.requireNonNullElse(message, "");
                author = Objects.requireNonNullElse(author, UserEmail.EMPTY);
                save(updated, message, author);
                try {
                    return reload(author + " " + message);
                } catch (RuntimeException failure) {
                    throw new ActivationFailedException(failure);
                }
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Configuration edit is closed");
            }
        }
    }

    private record Preparation(String message, UnaryOperator<ConfigurationFile> operation) {}
}
