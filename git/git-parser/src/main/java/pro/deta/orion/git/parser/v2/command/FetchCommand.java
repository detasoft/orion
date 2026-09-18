package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.fetch.*;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.data.FetchPlan;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.capability.GitCapability;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class FetchCommand implements GitCommand {
    private final GitStorageApi storage;
    private final GitCapabilities advertisedCapabilities;

    public FetchCommand(GitStorageApi storage, GitCapabilities advertisedCapabilities) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.advertisedCapabilities = Objects.requireNonNull(advertisedCapabilities, "advertisedCapabilities");
    }

    @Override
    public void action(GitProtocolContext protocolContext) throws IOException {
        FetchNegotiator negotiator = new FetchNegotiator(reader, writer, gitProtocolVersion);
        FetchRequest request = negotiator.readRequest();
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
        Optional<FetchPlan> plan = prepareResponse(negotiator.negotiate(iterator));
    }

    protected void checkFetchAccess(FetchRequest request) throws IOException {
    }

    public Optional<FetchPlan> prepareResponse(NegotiationContext context) {
        Objects.requireNonNull(context, "context");
        FetchRequest request = context.request();
        boolean readyPermitsPack = context.ready() && (request.mode() == FetchRequest.Mode.PROTOCOL_V2
                || request.capabilities().contains(GitCapability.NO_DONE.value()));
        if (!context.doneReceived() && !readyPermitsPack) {
            return Optional.empty();
        }
        Set<ObjectId> wants = context.wantedObjects();
        if (wants.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FetchPlan(wants, context.wantedRefs(), context.commonObjects(),
                request.shallowCommits(), request.depth(), request.deepenSince(), request.deepenNot(),
                request.filter(), request.capabilities(), request.packfileUriProtocols()));
    }

    public NegotiationContext negotiate(FetchNegotiatorIterator iterator) throws IOException {
        FetchRequest request = iterator.getContext().request();
        if (version == GitProtocolVersion.V2) {
            for (NegotiationMessage message : request.initialMessages()) {
                boolean more = iterator.next(message);
                writeResponses(iterator.getResponsesToSend(), request);
                if (!more) {
                    break;
                }
            }
        } else if (!request.wants().isEmpty()) {
            boolean more;
            do {
                more = iterator.next(readNegotiationMessage(input));
                writeResponses(iterator.getResponsesToSend(), request);
            } while (more);
        }
        return iterator.getContext();
    }

    private void writeResponses(List<NegotiationResponse> responsesToSend, FetchRequest request) throws IOException {
        if (!responsesToSend.isEmpty()) {
            SideBand sideBand = request.capabilities().contains(GitCapability.SIDEBAND_ALL.value())
                    ? SideBand.DATA : SideBand.NONE;
            output.writeNegotiationRound(responsesToSend, sideBand);
            output.flush();
        }
    }

    public static NegotiationMessage readNegotiationMessage(GitProtocolContext.Reader reader) throws IOException {
        GitPktLine packet = reader.readGitPktLine();
        return switch (packet) {
            case GitPktLine.Control.FLUSH -> NegotiationMessage.Control.END_ROUND;
            case GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END ->
                    throw invalid("Expected a data packet");
            case GitPktLine.Data data -> {
                NegotiationCapability argument = NegotiationCapability.parse(data.text());
                yield switch (argument.cap()) {
                    case DONE -> NegotiationMessage.Control.DONE;
                    case HAVE -> new NegotiationMessage.Have(new ObjectId(argument.value()));
                    default -> throw invalid("Unexpected negotiation message: " + argument.cap().wireName());
                };
            }
        };
    }


    private static IOException invalid(String message) {
        return new IOException(message);
    }
}
