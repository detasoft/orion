package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.util.Objects;

/**
 * Copies a verified pack into a mutable index and resolves deltas before transferring ownership.
 * Closing an unsuccessful ingestion discards its target; the caller always owns the input.
 */
public final class PackIngestor implements AutoCloseable {
    private final BufferedByteInputV2 input;
    private final MutableIndexedPack target;
    private final GitStorageApi storage;
    private boolean started;
    private boolean ownsTarget = true;

    public PackIngestor(BufferedByteInputV2 input, MutableIndexedPack target, GitStorageApi storage) {
        this.input = Objects.requireNonNull(input, "input");
        this.target = Objects.requireNonNull(target, "target");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public MutableIndexedPack ingest() throws IOException {
        if (started) {
            throw new IllegalStateException("Pack ingestion has already started or closed");
        }
        started = true;
        try (PackReader reader = new PackReader(input)) {
            boolean ended = false;
            while (!ended) {
                switch (reader.next()) {
                    case PackReadStep.Bytes bytes -> target.append(bytes.data());
                    case PackReadStep.EntryEnd end -> {
                        PackEntry entry = end.metadata();
                        target.addEntry(entry.offset(), entry.packOffset(), entry.inflatedSize(), entry.type(),
                                entry.baseOffset(), entry.baseId());
                        if (end.objectId().isPresent()) {
                            target.addObject(entry.offset(), end.objectId().orElseThrow(), entry.type(),
                                    entry.inflatedSize());
                        }
                    }
                    case PackReadStep.End end -> {
                        target.setId(end.id());
                        ended = true;
                    }
                }
            }
        }
        new GitPackObjectResolver(target, storage).complete();
        ownsTarget = false;
        return target;
    }

    @Override
    public void close() throws IOException {
        started = true;
        if (ownsTarget) {
            target.discard();
            ownsTarget = false;
        }
    }
}
