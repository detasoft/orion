package pro.deta.orion.git.nativestorage.service;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.pack.PackParseException;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCapability;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCapabilityResolution;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCapabilityResolver;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommand;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommandSection;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class NativeReceivePackService {
    private static final String NULL_ID = "0".repeat(40);
    private static final Set<ReceivePackCapability> SUPPORTED_CAPABILITIES = Set.of(
            ReceivePackCapability.REPORT_STATUS,
            ReceivePackCapability.SIDE_BAND_64K,
            ReceivePackCapability.OBJECT_FORMAT,
            ReceivePackCapability.AGENT);

    private final LooseRefStore refStore;
    private final LooseObjectStore objectStore;
    private final PackIngestor packIngestor;
    private final ReceivePackCapabilityResolver capabilityResolver = new ReceivePackCapabilityResolver();

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
        ReceivePackCapabilityResolution capabilityResolution =
                capabilityResolver.resolve(SUPPORTED_CAPABILITIES, commandSection.clientCapabilities());
        if (!capabilityResolution.accepted()) {
            return ReceiveResult.packFailure(
                    "unsupported capabilities: " + String.join(", ", capabilityResolution.rejected()));
        }

        for (ReceivePackCommand command : commands) {
            if (NULL_ID.equals(command.newId())) {
                return ReceiveResult.packFailure("delete commands are not supported");
            }
        }

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

        List<LooseRefStore.Update> updates = new ArrayList<>(commands.size());
        for (ReceivePackCommand command : commands) {
            updates.add(new LooseRefStore.Update(command.refName(), command.oldId(), command.newId()));
        }
        List<RefUpdateResult> updateResults =
                refStore.updateAll(updates, () -> objectStore.putAll(quarantine));

        List<ReceiveResult.RefResult> refResults = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            ReceivePackCommand command = commands.get(i);
            RefUpdateResult result = updateResults.get(i);
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
