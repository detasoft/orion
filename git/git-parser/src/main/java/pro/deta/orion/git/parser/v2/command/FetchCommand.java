package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiatorIterator;
import pro.deta.orion.git.parser.v2.fetch.FetchPack;
import pro.deta.orion.git.parser.v2.fetch.FetchPlan;
import pro.deta.orion.git.parser.v2.fetch.FetchRequest;
import pro.deta.orion.git.parser.v2.fetch.NegotiationCapability;
import pro.deta.orion.git.parser.v2.fetch.NegotiationCapabilityParser;
import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.fetch.NegotiationResponse;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class FetchCommand implements GitCommand {
    private final GitStorageApi storage;
    private final GitRepositoryContext repository;
    private final GitCapabilities advertisedCapabilities = new GitCapabilities();

    public FetchCommand(GitStorageApi storage, GitCapabilities advertisedCapabilities) {
        this(new GitRepositoryContext(storage), advertisedCapabilities);
    }

    public FetchCommand(GitRepositoryContext repository, GitCapabilities advertisedCapabilities) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storage = repository.storage();
        this.advertisedCapabilities.addAll(Objects.requireNonNull(advertisedCapabilities, "advertisedCapabilities"));
    }

    @Override
    public void action(GitProtocolContext protocolContext) throws IOException {
        GitProtocolContext.Reader reader = protocolContext.reader();
        GitProtocolContext.Writer writer = protocolContext.writer();
        FetchRequest request = FetchRequest.parseRequest(reader, protocolContext.version());
        FetchNegotiatorIterator iterator = prepareNegotiation(request, protocolContext.transport());
        if (request.mode() != FetchRequest.Mode.PROTOCOL_V2
                && (request.depth().isPresent() || request.deepenSince().isPresent()
                || !request.deepenNot().isEmpty())) {
            FetchPack history = FetchPack.prepare(storage, plan(iterator.getContext()));
            writer.writeShallowInfo(history.shallowCommits(), history.unshallowCommits(), SideBand.NONE);
            writer.flush();
        }
        NegotiationContext context = negotiate(iterator, reader, writer);
        Optional<FetchPlan> response = prepareResponse(context);
        if (response.isEmpty()) {
            return;
        }
        FetchPlan plan = response.orElseThrow();
        FetchPack pack = FetchPack.prepare(repository, plan);
        if (request.mode() == FetchRequest.Mode.PROTOCOL_V2) {
            SideBand sideBand = request.capabilities().has(GitCapability.SIDEBAND_ALL)
                    ? SideBand.DATA : SideBand.NONE;
            writer.writeShallowInfo(pack.shallowCommits(), pack.unshallowCommits(), sideBand);
        }
        BufferedByteOutput output = writer.beginPack(plan.capabilities(), plan.wantedRefs(),
                pack.packUris());
        try (PackWriter packWriter = new PackWriter(output, pack.objectCount())) {
            pack.writeTo(packWriter);
            packWriter.finish();
        }
        writer.endPack(plan.capabilities());
        writer.flush();
    }

    public FetchNegotiatorIterator prepareNegotiation(FetchRequest request, GitTransport transport)
            throws IOException {
        NegotiationContext context = new NegotiationContext(request, storage, advertisedCapabilities);
        FetchNegotiatorIterator iterator = new FetchNegotiatorIterator(context, transport);
        checkFetchAccess(request);
        if (!request.wantRefs().isEmpty()) {
            context.resolveWantedRefs(storage.snapshotRefs());
        }
        for (ObjectId objectId : context.wantedObjects()) {
            if (!storage.exists(objectId)) {
                throw new IOException("Wanted object does not exist: " + objectId.toHex());
            }
        }
        return iterator;
    }

    protected void checkFetchAccess(FetchRequest request) throws IOException {
        repository.checkFetchAccess(request);
    }

    public Optional<FetchPlan> prepareResponse(NegotiationContext context) {
        Objects.requireNonNull(context, "context");
        FetchRequest request = context.request();
        boolean readyPermitsPack = context.ready() && (request.mode() == FetchRequest.Mode.PROTOCOL_V2
                || request.capabilities().has(GitCapability.NO_DONE));
        if (!context.doneReceived() && !readyPermitsPack) {
            return Optional.empty();
        }
        Set<ObjectId> wants = context.wantedObjects();
        if (wants.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(plan(context));
    }

    private static FetchPlan plan(NegotiationContext context) {
        FetchRequest request = context.request();
        return new FetchPlan(context.wantedObjects(), context.wantedRefs(), context.commonObjects(),
                request.shallowCommits(), request.depth(), request.deepenSince(), request.deepenNot(),
                request.filter(), request.capabilities(), request.packfileUriProtocols());
    }

    public NegotiationContext negotiate(FetchNegotiatorIterator iterator, GitProtocolContext.Reader reader,
                                         GitProtocolContext.Writer writer) throws IOException {
        FetchRequest request = iterator.getContext().request();
        if (request.mode() == FetchRequest.Mode.PROTOCOL_V2) {
            for (NegotiationMessage message : request.initialMessages()) {
                boolean more = iterator.next(message);
                writeResponses(writer, iterator.getResponsesToSend(), request);
                if (!more) {
                    break;
                }
            }
        } else if (!request.wants().isEmpty()) {
            boolean more;
            do {
                more = iterator.next(readNegotiationMessage(reader));
                writeResponses(writer, iterator.getResponsesToSend(), request);
            } while (more);
        }
        return iterator.getContext();
    }

    private void writeResponses(GitProtocolContext.Writer writer, List<NegotiationResponse> responsesToSend,
                                FetchRequest request) throws IOException {
        if (!responsesToSend.isEmpty()) {
            SideBand sideBand = request.capabilities().has(GitCapability.SIDEBAND_ALL)
                    ? SideBand.DATA : SideBand.NONE;
            writer.writeNegotiationRound(responsesToSend, sideBand);
            writer.flush();
        }
    }

    public static NegotiationMessage readNegotiationMessage(GitProtocolContext.Reader reader) throws IOException {
        GitPktLine packet = reader.readGitPktLine();
        return switch (packet) {
            case GitPktLine.Control.FLUSH -> NegotiationMessage.Control.END_ROUND;
            case GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END ->
                    throw invalid("Expected a data packet");
            case GitPktLine.Data data -> {
                NegotiationCapabilityParser parser = new NegotiationCapabilityParser(GitProtocolVersion.V0);
                GitCapabilityValue argument = parser.parse(packet).getFirst();
                try {
                    yield switch (NegotiationCapability.findByWireName(argument.name()).orElse(null)) {
                        case DONE -> NegotiationMessage.Control.DONE;
                        case HAVE -> new NegotiationMessage.Have(new ObjectId(argument.value().orElseThrow()));
                        case null, default -> throw invalid("Unexpected negotiation message: " + argument.name());
                    };
                } catch (IllegalArgumentException error) {
                    throw new IOException("Invalid negotiation object ID", error);
                }
            }
        };
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }
}
