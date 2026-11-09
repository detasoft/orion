package pro.deta.orion.config;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.OrionEvents;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.BlobStorage.BlobStorageOperations;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.VcChangeAuthor;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcModificationException;
import pro.deta.orion.util.VcResourceModified;
import pro.deta.orion.util.VcRevision;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Objects;

/** Decodes versioned XML from a blob and publishes only valid configuration changes. */
@Slf4j
public final class BlobOrionConfigurationStorage implements OrionVersionedConfigurationStorage, AutoCloseable {
    private final OrionDocumentCodec codec = new OrionDocumentCodec();
    private final BlobStorage source;
    private final OrionEvents events;

    public BlobOrionConfigurationStorage(BlobStorage source, OrionEventManager events) {
        this.source = Objects.requireNonNull(source, "source");
        this.events = events.forTarget(this);
        this.events.subscribe(VcResourceModified.class, source, this::onChanged);
    }

    @Override
    public OrionDocumentRevision load() {
        return read(new VcRevision.Latest());
    }

    @Override
    public OrionDocumentRevision load(VcRevision.Stored revision) {
        return read(Objects.requireNonNull(revision, "revision"));
    }

    private OrionDocumentRevision read(VcRevision.ReadTarget revision) {
        try (BlobStorageOperations operation = source.open(revision)) {
            Result<OrionDocument> result;
            try {
                byte[] bytes = operation.readContent();
                try {
                    result = Result.of(codec.read(new ByteArrayInputStream(bytes)));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            } catch (UncheckedIOException failure) {
                result = failure.getCause() instanceof FileNotFoundException
                        ? new Result.Failure<>(Result.FailureCode.NOT_FOUND)
                        : new Result.Failure<>(Result.FailureCode.GENERAL, failure.getMessage(), failure);
            } catch (IOException | RuntimeException failure) {
                result = new Result.Failure<>(Result.FailureCode.GENERAL, failure.getMessage(), failure);
            }
            return new OrionDocumentRevision(result, operation.revision());
        }
    }

    @Override
    public VcRevision.Stored save(VcRevision expectedRevision, OrionDocument document, ChangeMetadata metadata) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(metadata.author(), "author");
        byte[] content = serialize(document);
        VcRevision.ReadTarget target = switch (expectedRevision) {
            case VcRevision.Stored stored -> stored;
            case VcRevision.Absent absent -> absent;
        };
        try (BlobStorageOperations operation = source.open(target)) {
            operation.write(content);
            return operation.commit(new VcChangeDescription(
                    new VcChangeAuthor(metadata.author().getUsername(), metadata.author().getEmail()),
                    metadata.message()));
        } catch (VcModificationException failure) {
            throw new OrionConfigurationConcurrentUpdateException("Orion configuration changed concurrently", failure);
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    private byte[] serialize(OrionDocument document) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            codec.write(document, output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot serialize configuration", failure);
        }
    }

    private void onChanged(VcResourceModified event) {
        if (!(event.revision() instanceof VcRevision.Stored revision)) return;
        OrionDocumentRevision snapshot = load(revision);
        switch (snapshot.result()) {
            case Result.Success<OrionDocument> ignored -> events.publish(new ConfigurationChanged(this, snapshot));
            case Result.Failure<OrionDocument> failure -> {
                if (failure.code() == Result.FailureCode.NOT_FOUND) {
                    log.warn("Configuration file is missing at {}; skipping notification", revision.value());
                } else {
                    log.warn("Cannot read configuration at {}; skipping notification",
                            revision.value(), failure.throwable());
                }
            }
        }
    }

    @Override
    public void close() {
        events.unsubscribe(VcResourceModified.class, source);
        if (source instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot close configuration source", failure);
            }
        }
    }
}
