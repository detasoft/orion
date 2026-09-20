package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.push.PushRequest;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PushCommand implements GitCommand {
    private final GitStorageApi storage;
    private final GitCapabilities advertisedCapabilities;

    public PushCommand(GitStorageApi storage, GitCapabilities advertisedCapabilities) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.advertisedCapabilities = new GitCapabilities(advertisedCapabilities);
    }

    @Override
    public void action(GitProtocolContext protocolContext) throws IOException {
        if (protocolContext.version() == GitProtocolVersion.V2) {
            throw new IOException("receive-pack requires the legacy push protocol");
        }
        PushRequest request = PushRequest.parse(protocolContext.reader(), advertisedCapabilities);
        if (request.updates().isEmpty()) {
            return;
        }
        boolean unpacked = true;
        List<RefUpdateResult> results;
        try {
            if (request.requiresPack()) {
                receivePack(protocolContext.input());
            }
        } catch (IOException failure) {
            if (!request.capabilities().has(GitCapability.REPORT_STATUS)
                    && !request.capabilities().has(GitCapability.REPORT_STATUS_V2)) {
                throw failure;
            }
            unpacked = false;
        }
        if (!unpacked) {
            results = new ArrayList<>(request.updates().size());
            for (RefUpdate update : request.updates()) {
                results.add(new RefUpdateResult(update, RefUpdateResult.Status.STORAGE_ERROR, Optional.empty()));
            }
        } else {
            results = storage.updateRefs(request.updates(), request.capabilities().has(GitCapability.ATOMIC));
        }
        protocolContext.writer().writePushStatus(request.capabilities(), unpacked, results);
    }

    private void receivePack(BufferedByteInputV2 input) throws IOException {
        try (PackIngestor ingestor = new PackIngestor(input, storage.newPack())) {
            IndexedPack pack = ingestor.ingest();
            try {
                new GitPackObjectResolver(pack, storage).complete();
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    pack.discard();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
            storage.persist(pack);
        }
    }
}
