package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.GitReader;
import pro.deta.orion.git.parser.v2.GitWriter;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData.ProtocolVersion;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Owns the wire loop around an object-based FetchNegotiatorIterator.
 * readRequest selects FetchRequest.parseLegacy or parseV2 using the version supplied by bootstrap.
 * Request grammar and cross-argument validation belong to FetchRequest. This class parses only the
 * subsequent legacy negotiation messages and leaves request capability and access checks to preparation.
 *
 * <p>readRequest returns a fresh parsed request without processing negotiation messages or writing replies.
 * FetchCommand prepares that request before passing its iterator to negotiate, which drives the wire loop
 * and returns the accumulated context. The iterator owns common-object and readiness decisions without parsing bytes.
 * V2 feeds only already parsed initialMessages; legacy reads one message at a time and flushes replies
 * before reading more. An empty legacy request finishes without reading negotiation messages.
 * Input/output are borrowed and never closed here. NegotiationContext owns checks through its borrowed storage;
 * the wire loop does not access storage. HTTP ends a legacy exchange at its round boundary without
 * equating it to pack readiness.
 * GitWriter frames replies; only v2 sideband-all prefixes negotiation data with a channel byte.
 * Production repository readiness checks and pack transfer remain pending; this is not a complete fetch exchange.
 */
public final class FetchNegotiator {
    private final GitReader input;
    private final GitWriter output;
    private final ProtocolVersion version;

    public FetchNegotiator(GitReader input, GitWriter output, ProtocolVersion version) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.version = Objects.requireNonNull(version, "version");
    }

    public FetchRequest readRequest() throws IOException {
        return switch (version) {
            case V0, V1 -> FetchRequest.parseLegacy(input);
            case V2 -> FetchRequest.parseV2(input);
        };
    }

    public NegotiationContext negotiate(FetchNegotiatorIterator iterator) throws IOException {
        FetchRequest request = iterator.getContext().request();
        if (version == ProtocolVersion.V2) {
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
            SideBand sideBand = version == ProtocolVersion.V2
                    && request.capabilities().contains(GitCapability.SIDEBAND_ALL.entry())
                    ? SideBand.DATA : SideBand.NONE;
            output.writeNegotiationRound(responsesToSend, version, sideBand);
            output.flush();
        }
    }

    public static NegotiationMessage readNegotiationMessage(GitReader reader) throws IOException {
        GitPktLine packet = reader.readGitPktLine();
        return switch (packet) {
            case GitPktLine.Control.FLUSH -> NegotiationMessage.Control.END_ROUND;
            case GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END ->
                    throw invalid("Expected a data packet");
            case GitPktLine.Data data -> {
                NegotiationCapability argument = NegotiationCapability.parse(data.text());
                yield switch (argument.cap()) {
                    case DONE -> NegotiationMessage.Control.DONE;
                    case HAVE -> new NegotiationMessage.Have(objectId(argument.value()));
                    default -> throw invalid("Unexpected negotiation message: " + argument.cap().wireName());
                };
            }
        };
    }

    private static ObjectId objectId(String value) throws IOException {
        try {
            return new ObjectId(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid fetch object ID", error);
        }
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }

}
