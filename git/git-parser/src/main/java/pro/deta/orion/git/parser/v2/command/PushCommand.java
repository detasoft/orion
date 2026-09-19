package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.util.Objects;

public final class PushCommand implements GitCommand {
    private final GitStorageApi storage;

    public PushCommand(GitStorageApi storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public void action(GitProtocolContext protocolContext) throws IOException {
        BufferedByteInputV2 input = protocolContext.input();
        try (PackIngestor<IndexedPack> ingestor = new PackIngestor<>(input, storage.newPack());
                IndexedPack pack = ingestor.ingest()) {
            storage.persist(pack);
        }
    }
}
