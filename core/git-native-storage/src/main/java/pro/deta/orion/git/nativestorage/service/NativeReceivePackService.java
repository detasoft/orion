package pro.deta.orion.git.nativestorage.service;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitRefUpdate;
import pro.deta.orion.git.common.GitRefUpdateResult;
import pro.deta.orion.git.common.GitRefUpdateType;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.pack.PackParseException;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackAdvertisementWriter;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCapability;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCapabilityResolution;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCapabilityResolver;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommand;
import pro.deta.orion.git.parser.wire.receivepack.ReceivePackCommandSection;
import pro.deta.orion.git.parser.wire.reportstatus.GitReportStatus;
import pro.deta.orion.git.parser.wire.reportstatus.GitReportStatusRef;
import pro.deta.orion.git.parser.wire.reportstatus.GitReportStatusWriter;
import pro.deta.orion.git.parser.wire.sideband.GitSideBandBand;
import pro.deta.orion.git.parser.wire.sideband.GitSideBandMode;
import pro.deta.orion.git.parser.wire.sideband.GitSideBandWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class NativeReceivePackService {
    private static final String NULL_ID = "0".repeat(40);
    private static final String BRANCH_PREFIX = "refs/heads/";
    private static final String TAG_PREFIX = "refs/tags/";

    private final LooseRefStore refStore;
    private final LooseObjectStore objectStore;
    private final PackIngestor packIngestor;
    private final ReceivePackPolicy policy;
    private final ByteBufAllocator allocator;
    private final ReceivePackCapabilityResolver capabilityResolver = new ReceivePackCapabilityResolver();
    private final ReceivePackAdvertisementWriter advertisementWriter = new ReceivePackAdvertisementWriter();
    private final GitReportStatusWriter reportStatusWriter = new GitReportStatusWriter();
    private final GitPktLineWriter pktLineWriter;

    public NativeReceivePackService(
            LooseRefStore refStore,
            LooseObjectStore objectStore,
            PackIngestor packIngestor) {
        this(
                refStore,
                objectStore,
                packIngestor,
                ReceivePackPolicy.conservative(),
                UnpooledByteBufAllocator.DEFAULT);
    }

    public NativeReceivePackService(
            LooseRefStore refStore,
            LooseObjectStore objectStore,
            PackIngestor packIngestor,
            ReceivePackPolicy policy,
            ByteBufAllocator allocator) {
        this.refStore = Objects.requireNonNull(refStore, "refStore");
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.packIngestor = Objects.requireNonNull(packIngestor, "packIngestor");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.pktLineWriter = new GitPktLineWriter(this.allocator);
    }

    public List<ByteBuf> advertise() {
        return advertisementWriter.write(pktLineWriter, advertisedRefs(), advertisedCapabilities());
    }

    public ReceiveResult receive(ReceivePackCommandSection commandSection, ByteBuf packBuffer) {
        Objects.requireNonNull(commandSection, "commandSection");
        Objects.requireNonNull(packBuffer, "packBuffer");

        ReceivePackCapabilityResolution capabilityResolution =
                capabilityResolver.resolve(advertisedCapabilities(), commandSection.clientCapabilities());
        if (!capabilityResolution.accepted()) {
            return ReceiveResult.packFailure(
                    "unsupported capabilities: " + String.join(", ", capabilityResolution.rejected()));
        }

        List<ReceivePackCommand> commands = commandSection.commands();
        if (commands.isEmpty()) {
            return ReceiveResult.success(List.of(), List.of());
        }

        List<CommandOutcome> policyOutcomes = validatePolicy(commands);
        if (hasRejected(policyOutcomes)) {
            return rejectedTransaction(policyOutcomes);
        }

        LooseObjectStore quarantine;
        if (requiresPack(commands)) {
            try {
                quarantine = packIngestor.ingest(packBuffer, objectStore);
            } catch (PackParseException e) {
                return ReceiveResult.packFailure(sanitizeError(e.getMessage()));
            }
        } else {
            quarantine = new LooseObjectStore();
        }

        List<CommandOutcome> objectOutcomes = validateObjectsAndFastForward(commands, quarantine);
        if (hasRejected(objectOutcomes)) {
            return rejectedTransaction(objectOutcomes);
        }

        List<LooseRefStore.Update> updates = new ArrayList<>(commands.size());
        for (ReceivePackCommand command : commands) {
            updates.add(new LooseRefStore.Update(command.refName(), command.oldId(), command.newId()));
        }
        List<RefUpdateResult> updateResults =
                refStore.updateAll(updates, () -> objectStore.putAll(quarantine));

        List<ReceiveResult.RefResult> refResults = new ArrayList<>(commands.size());
        List<GitRefUpdate> refUpdates = new ArrayList<>(commands.size());
        boolean transactionFailed = hasStale(updateResults);
        for (int i = 0; i < commands.size(); i++) {
            ReceivePackCommand command = commands.get(i);
            RefUpdateResult result = updateResults.get(i);
            GitRefUpdateResult eventResult = transactionFailed && result != RefUpdateResult.STALE
                    ? GitRefUpdateResult.LOCK_FAILURE
                    : toGitRefUpdateResult(result);
            if (eventResult == GitRefUpdateResult.OK) {
                refResults.add(ReceiveResult.RefResult.ok(command.refName()));
            } else if (transactionFailed && result != RefUpdateResult.STALE) {
                refResults.add(ReceiveResult.RefResult.ng(command.refName(), "atomic transaction failed"));
            } else {
                refResults.add(ReceiveResult.RefResult.ng(command.refName(), "stale info"));
            }
            refUpdates.add(toGitRefUpdate(command, toGitRefUpdateType(command), eventResult));
        }

        return ReceiveResult.success(refResults, refUpdates);
    }

    public List<ByteBuf> reportStatus(ReceivePackCommandSection commandSection, ReceiveResult result) {
        Objects.requireNonNull(commandSection, "commandSection");
        Objects.requireNonNull(result, "result");
        ReceivePackCapabilityResolution capabilityResolution =
                capabilityResolver.resolve(advertisedCapabilities(), commandSection.clientCapabilities());
        if (!capabilityResolution.uses(ReceivePackCapability.REPORT_STATUS)) {
            return List.of();
        }

        List<ByteBuf> statusPackets = reportStatusWriter.write(pktLineWriter, toReportStatus(result));
        if (!capabilityResolution.uses(ReceivePackCapability.SIDE_BAND_64K)) {
            return statusPackets;
        }

        ByteBuf payload = concat(statusPackets);
        try {
            GitSideBandWriter sideBandWriter = new GitSideBandWriter(allocator, GitSideBandMode.SIDE_BAND_64K);
            List<ByteBuf> sideBandPackets = new ArrayList<>(sideBandWriter.write(GitSideBandBand.DATA, payload));
            sideBandPackets.add(pktLineWriter.writeFlush());
            return List.copyOf(sideBandPackets);
        } finally {
            payload.release();
            releaseAll(statusPackets);
        }
    }

    private Set<ReceivePackCapability> advertisedCapabilities() {
        EnumSet<ReceivePackCapability> capabilities = EnumSet.of(
                ReceivePackCapability.REPORT_STATUS,
                ReceivePackCapability.SIDE_BAND_64K,
                ReceivePackCapability.OFS_DELTA,
                ReceivePackCapability.ATOMIC,
                ReceivePackCapability.OBJECT_FORMAT,
                ReceivePackCapability.AGENT);
        if (policy.allowBranchDeletes() || policy.allowTagDeletes()) {
            capabilities.add(ReceivePackCapability.DELETE_REFS);
        }
        return capabilities;
    }

    private Map<String, String> advertisedRefs() {
        Map<String, String> snapshot = refStore.snapshot();
        TreeMap<String, String> refs = new TreeMap<>();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            String refName = entry.getKey();
            if (refName.startsWith(BRANCH_PREFIX) || refName.startsWith(TAG_PREFIX)) {
                refs.put(refName, entry.getValue());
            }
        }
        return refs;
    }

    private List<CommandOutcome> validatePolicy(List<ReceivePackCommand> commands) {
        List<CommandOutcome> outcomes = new ArrayList<>(commands.size());
        for (ReceivePackCommand command : commands) {
            outcomes.add(validateCommandPolicy(command));
        }
        return outcomes;
    }

    private CommandOutcome validateCommandPolicy(ReceivePackCommand command) {
        GitRefUpdateType type = toGitRefUpdateType(command);
        if (policy.isProtected(command.refName())) {
            return CommandOutcome.rejected(
                    command,
                    "protected ref",
                    GitRefUpdateResult.REJECTED_OTHER_REASON,
                    type);
        }
        if (command.refName().startsWith(BRANCH_PREFIX)) {
            if (command.isDelete() && !policy.allowBranchDeletes()) {
                return CommandOutcome.rejected(
                        command,
                        "branch deletes are not allowed",
                        GitRefUpdateResult.REJECTED_NO_DELETE,
                        type);
            }
            return CommandOutcome.accepted(command, type);
        }
        if (command.refName().startsWith(TAG_PREFIX)) {
            if (command.isCreate() && policy.allowTagCreates()) {
                return CommandOutcome.accepted(command, type);
            }
            if (command.isUpdate() && policy.allowTagUpdates()) {
                return CommandOutcome.accepted(command, type);
            }
            if (command.isDelete() && policy.allowTagDeletes()) {
                return CommandOutcome.accepted(command, type);
            }
            GitRefUpdateResult result = command.isCreate()
                    ? GitRefUpdateResult.REJECTED_NO_CREATE
                    : command.isDelete()
                    ? GitRefUpdateResult.REJECTED_NO_DELETE
                    : GitRefUpdateResult.REJECTED_OTHER_REASON;
            return CommandOutcome.rejected(command, "tag update policy rejected", result, type);
        }
        return CommandOutcome.rejected(
                command,
                "unsupported ref namespace",
                GitRefUpdateResult.REJECTED_OTHER_REASON,
                type);
    }

    private List<CommandOutcome> validateObjectsAndFastForward(
            List<ReceivePackCommand> commands,
            LooseObjectStore quarantine) {
        List<CommandOutcome> outcomes = new ArrayList<>(commands.size());
        for (ReceivePackCommand command : commands) {
            GitRefUpdateType type = toGitRefUpdateType(command);
            if (command.isDelete()) {
                outcomes.add(CommandOutcome.accepted(command, type));
                continue;
            }
            GitObjectId newId = GitObjectId.of(command.newId());
            if (!quarantine.contains(newId) && !objectStore.contains(newId)) {
                outcomes.add(CommandOutcome.rejected(
                        command,
                        "missing object " + command.newId(),
                        GitRefUpdateResult.REJECTED_MISSING_OBJECT,
                        type));
                continue;
            }
            if (command.isUpdate()
                    && command.refName().startsWith(BRANCH_PREFIX)
                    && !policy.allowNonFastForwardUpdates()
                    && isCurrentOldId(command)
                    && !NULL_ID.equals(command.oldId())
                    && !command.oldId().equals(command.newId())
                    && !isFastForward(GitObjectId.of(command.oldId()), newId, quarantine)) {
                outcomes.add(CommandOutcome.rejected(
                        command,
                        "non-fast-forward",
                        GitRefUpdateResult.REJECTED_NON_FAST_FORWARD,
                        GitRefUpdateType.UPDATE_NON_FAST_FORWARD));
                continue;
            }
            outcomes.add(CommandOutcome.accepted(command, type));
        }
        return outcomes;
    }

    private boolean isCurrentOldId(ReceivePackCommand command) {
        return refStore.read(command.refName())
                .map(id -> id.value().equals(command.oldId()))
                .orElse(false);
    }

    private boolean isFastForward(GitObjectId oldId, GitObjectId newId, LooseObjectStore quarantine) {
        if (oldId.equals(newId)) {
            return true;
        }
        ArrayDeque<GitObjectId> pending = new ArrayDeque<>();
        Set<GitObjectId> visited = new java.util.HashSet<>();
        pending.add(newId);
        while (!pending.isEmpty()) {
            GitObjectId current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(oldId)) {
                return true;
            }
            Optional<LooseObject> object = readObject(current, quarantine);
            if (object.isEmpty() || object.get().type() != ObjectType.COMMIT) {
                return false;
            }
            addParents(object.get().data(), pending);
        }
        return false;
    }

    private Optional<LooseObject> readObject(GitObjectId id, LooseObjectStore quarantine) {
        return quarantine.read(id).or(() -> objectStore.read(id));
    }

    private static void addParents(byte[] commitData, ArrayDeque<GitObjectId> pending) {
        String commit = new String(commitData, StandardCharsets.US_ASCII);
        String[] lines = commit.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                return;
            }
            if (line.startsWith("parent ")) {
                pending.addLast(GitObjectId.of(line.substring("parent ".length())));
            }
        }
    }

    private static boolean requiresPack(List<ReceivePackCommand> commands) {
        for (ReceivePackCommand command : commands) {
            if (!command.isDelete()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRejected(List<CommandOutcome> outcomes) {
        for (CommandOutcome outcome : outcomes) {
            if (!outcome.accepted()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStale(List<RefUpdateResult> updateResults) {
        for (RefUpdateResult result : updateResults) {
            if (result == RefUpdateResult.STALE) {
                return true;
            }
        }
        return false;
    }

    private static ReceiveResult rejectedTransaction(List<CommandOutcome> outcomes) {
        List<ReceiveResult.RefResult> refResults = new ArrayList<>(outcomes.size());
        List<GitRefUpdate> refUpdates = new ArrayList<>(outcomes.size());
        for (CommandOutcome outcome : outcomes) {
            CommandOutcome finalOutcome = outcome.accepted()
                    ? CommandOutcome.rejected(
                    outcome.command(),
                    "transaction rejected",
                    GitRefUpdateResult.REJECTED_OTHER_REASON,
                    outcome.type())
                    : outcome;
            refResults.add(ReceiveResult.RefResult.ng(finalOutcome.command().refName(), finalOutcome.reason()));
            refUpdates.add(toGitRefUpdate(
                    finalOutcome.command(),
                    finalOutcome.type(),
                    finalOutcome.result()));
        }
        return ReceiveResult.success(refResults, refUpdates);
    }

    private static GitReportStatus toReportStatus(ReceiveResult result) {
        List<GitReportStatusRef> refs = new ArrayList<>(result.refResults().size());
        for (ReceiveResult.RefResult ref : result.refResults()) {
            refs.add(ref.ok()
                    ? GitReportStatusRef.ok(ref.refName())
                    : GitReportStatusRef.ng(ref.refName(), ref.reason()));
        }
        if (result.packAccepted()) {
            return GitReportStatus.unpackOk(refs);
        }
        return GitReportStatus.unpackError(sanitizeError(result.packError()), refs);
    }

    private static GitRefUpdate toGitRefUpdate(
            ReceivePackCommand command,
            GitRefUpdateType type,
            GitRefUpdateResult result) {
        return new GitRefUpdate(
                command.refName(),
                GitObjectId.of(command.oldId()),
                GitObjectId.of(command.newId()),
                type,
                result);
    }

    private static GitRefUpdateType toGitRefUpdateType(ReceivePackCommand command) {
        if (command.isCreate()) {
            return GitRefUpdateType.CREATE;
        }
        if (command.isDelete()) {
            return GitRefUpdateType.DELETE;
        }
        return GitRefUpdateType.UPDATE;
    }

    private static GitRefUpdateResult toGitRefUpdateResult(RefUpdateResult result) {
        return switch (result) {
            case CREATED, FAST_FORWARD, DELETED, NO_OP -> GitRefUpdateResult.OK;
            case STALE -> GitRefUpdateResult.LOCK_FAILURE;
        };
    }

    private ByteBuf concat(List<ByteBuf> packets) {
        int size = 0;
        for (ByteBuf packet : packets) {
            size += packet.readableBytes();
        }
        ByteBuf result = allocator.buffer(size, size);
        for (ByteBuf packet : packets) {
            result.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        }
        return result;
    }

    private static void releaseAll(List<ByteBuf> packets) {
        for (ByteBuf packet : packets) {
            packet.release();
        }
    }

    private static String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return "pack processing failed";
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private record CommandOutcome(
            ReceivePackCommand command,
            boolean accepted,
            String reason,
            GitRefUpdateResult result,
            GitRefUpdateType type) {

        private CommandOutcome {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(type, "type");
            if (!accepted && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("Rejected command outcome must include a reason");
            }
        }

        static CommandOutcome accepted(ReceivePackCommand command, GitRefUpdateType type) {
            return new CommandOutcome(command, true, null, GitRefUpdateResult.NOT_ATTEMPTED, type);
        }

        static CommandOutcome rejected(
                ReceivePackCommand command,
                String reason,
                GitRefUpdateResult result,
                GitRefUpdateType type) {
            return new CommandOutcome(command, false, reason, result, type);
        }
    }
}
