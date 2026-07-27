package pro.deta.orion.git.nativestorage.service;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.pack.PackParseException;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommand;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommandSection;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NativeReceivePackService {
    private final LooseRefStore refStore;
    private final LooseObjectStore objectStore;
    private final PackIngestor packIngestor;

    public NativeReceivePackService(
            LooseRefStore refStore,
            LooseObjectStore objectStore,
            PackIngestor packIngestor) {
        this.refStore = Objects.requireNonNull(refStore, "refStore");
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.packIngestor = Objects.requireNonNull(packIngestor, "packIngestor");
    }

    public ReceiveResult receive(ReceivePackCommandSection commandSection, InputStream packStream) {
        Objects.requireNonNull(commandSection, "commandSection");
        Objects.requireNonNull(packStream, "packStream");

        List<ReceivePackCommand> commands = commandSection.commands();

        LooseObjectStore quarantine;
        try {
            quarantine = packIngestor.ingest(packStream);
        } catch (PackParseException e) {
            return ReceiveResult.packFailure(sanitizeError(e.getMessage()));
        }

        for (ReceivePackCommand command : commands) {
            GitObjectId newId = GitObjectId.of(command.newId());
            if (!quarantine.contains(newId) && !objectStore.contains(newId)) {
                return ReceiveResult.packFailure("missing object " + command.newId());
            }
        }

        objectStore.putAll(quarantine);

        List<ReceiveResult.RefResult> refResults = new ArrayList<>();
        for (ReceivePackCommand command : commands) {
            RefUpdateResult result = refStore.update(command.refName(), command.oldId(), command.newId());
            refResults.add(switch (result) {
                case CREATED, FAST_FORWARD, NO_OP -> ReceiveResult.RefResult.ok(command.refName());
                case STALE -> ReceiveResult.RefResult.ng(command.refName(), "stale info");
            });
        }

        return ReceiveResult.success(refResults);
    }

    private static String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return "pack processing failed";
        }
        return message;
    }
}
