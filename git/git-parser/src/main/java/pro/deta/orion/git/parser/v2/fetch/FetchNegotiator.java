package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.GitReader;
import pro.deta.orion.git.parser.v2.GitWriter;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.capability.GitObjectFormat;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData.ProtocolVersion;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Owns fetch request parsing and the wire loop around an object-based FetchNegotiatorIterator.
 * parseLegacyRequest handles the v0/v1 want-section grammar and stops at its flush without
 * consuming haves. parseV2Request starts after the command/capability header delimiter and consumes fetch
 * arguments through their flush; already parsed haves/done are retained in FetchRequest for the iterator.
 * None of these methods consumes another request or closes the borrowed BufferedByteInput.
 *
 * <p>Version is supplied by bootstrap, not inferred from wants. Parsing uses GitReader and the existing
 * pkt-line decoder; NegotiationCapability extracts argument names and values. Parsing accepts an optional
 * trailing LF, validates IDs and argument combinations, preserves
 * pack options, and reports malformed/truncated requests as IOException. Legacy's empty initial flush is
 * an orderly no-fetch request. V2 requires at least one want or want-ref. Unknown v2 arguments are rejected.
 * Capability advertisement, object access, ref resolution, and filter execution belong to FetchCommand;
 * parsing does not imply that an extension is enabled or that any requested object exists.
 *
 * <p>negotiate parses the request, creates the iterator, writes replies including the terminal batch,
 * and returns the accumulated context. The iterator owns common-object and readiness decisions without parsing bytes.
 * V2 feeds only already parsed initialMessages; legacy reads one message at a time and flushes replies
 * before reading more. An empty legacy request finishes without reading negotiation messages.
 * Input/output are borrowed and never closed here. FetchCommand supplies Checks; storage never enters this
 * wire loop. stateless ends a legacy exchange at its round boundary without equating it to pack readiness.
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

    public NegotiationContext negotiate(FetchNegotiatorIterator.Checks checks, boolean stateless) throws IOException {
        FetchRequest request = switch (version) {
            case V0, V1 -> parseLegacyRequest(input);
            case V2 -> parseV2Request(input);
        };
        FetchNegotiatorIterator iterator = new FetchNegotiatorIterator(request, checks, stateless);
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
                NegotiationCapabilityValue argument = NegotiationCapability.parse(data.text());
                yield switch (argument.cap()) {
                    case DONE -> NegotiationMessage.Control.DONE;
                    case HAVE -> new NegotiationMessage.Have(objectId(argument.value()));
                    default -> throw invalid("Unexpected negotiation message: " + argument.cap().wireName());
                };
            }
        };
    }

    public static FetchRequest parseV2Request(GitReader reader) throws IOException {
        FetchRequest request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        while (true) {
            GitPktLine packet = reader.readGitPktLine();
            switch (packet) {
                case GitPktLine.Control.FLUSH -> {
                    if (request.wants().isEmpty() && request.wantRefs().isEmpty()) {
                        throw invalid("Fetch requires want or want-ref");
                    }
                    request.initialMessages().add(NegotiationMessage.Control.END_ROUND);
                    validate(request);
                    return request;
                }
                case GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END ->
                        throw invalid("Expected a data packet");
                case GitPktLine.Data data -> {
                    NegotiationCapabilityValue argument = NegotiationCapability.parse(data.text());
                    switch (argument.cap()) {
                        case WANT -> request.wants().add(objectId(argument.value()));
                        case HAVE -> request.initialMessages().add(
                                new NegotiationMessage.Have(objectId(argument.value())));
                        case DONE -> {
                            if (request.initialMessages().contains(NegotiationMessage.Control.DONE)) {
                                throw invalid("Duplicate done");
                            }
                            request.initialMessages().add(NegotiationMessage.Control.DONE);
                        }
                        case WANT_REF -> {
                            String ref = token(argument.value());
                            validateWantedRef(ref);
                            request.wantRefs().add(ref);
                        }
                        case PACKFILE_URIS -> {
                            if (!request.packfileUriProtocols().isEmpty()) {
                                throw invalid("Duplicate packfile-uris");
                            }
                            for (String protocol : argument.value().split(",", -1)) {
                                if (!protocol.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
                                    throw invalid("Invalid packfile URI protocol");
                                }
                                request.packfileUriProtocols().add(protocol);
                            }
                        }
                        case THIN_PACK, OFS_DELTA, INCLUDE_TAG, NO_PROGRESS, WAIT_FOR_DONE,
                             SIDEBAND_ALL, DEEPEN_RELATIVE -> request.capabilities().add(argument.cap().entry());
                        default -> acceptShared(request, argument);
                    }
                }
            }
        }
    }

    public static FetchRequest parseLegacyRequest(GitReader reader) throws IOException {
        FetchRequest request = new FetchRequest();
        boolean receivedLine = false;
        boolean wantsEnded = false;
        while (true) {
            GitPktLine packet = reader.readGitPktLine();
            switch (packet) {
                case GitPktLine.Control.FLUSH -> {
                    if (receivedLine && request.wants().isEmpty()) {
                        throw invalid("Legacy fetch requires want");
                    }
                    FetchRequest.Mode mode = request.capabilities().contains(GitCapability.MULTI_ACK_DETAILED.entry())
                            ? FetchRequest.Mode.MULTI_ACK_DETAILED
                            : request.capabilities().contains(GitCapability.MULTI_ACK.entry())
                                    ? FetchRequest.Mode.MULTI_ACK : FetchRequest.Mode.SINGLE_ACK;
                    request.setMode(mode);
                    validate(request);
                    return request;
                }
                case GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END ->
                        throw invalid("Expected a data packet");
                case GitPktLine.Data data -> {
                    NegotiationCapabilityValue argument = NegotiationCapability.parse(data.text());
                    receivedLine = true;
                    if (argument.cap() == GitCapability.WANT) {
                        if (wantsEnded) {
                            throw invalid("Want after legacy request options");
                        }
                        String[] values = argument.value().split(" ", -1);
                        boolean firstWant = request.wants().isEmpty();
                        if (!firstWant && values.length != 1) {
                            throw invalid("Capabilities are only allowed on the first want");
                        }
                        request.wants().add(objectId(values[0]));
                        for (int i = 1; i < values.length; i++) {
                            request.capabilities().add(GitCapability.parse(values[i], GitObjectFormat.SHA1));
                        }
                    } else {
                        if (request.wants().isEmpty()) {
                            throw invalid("Legacy request must start with want");
                        }
                        wantsEnded = true;
                        acceptShared(request, argument);
                    }
                }
            }
        }
    }

    private static ObjectId objectId(String value) throws IOException {
        try {
            return new ObjectId(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid fetch object ID", error);
        }
    }

    private static String token(String value) throws IOException {
        if (value.isEmpty() || value.indexOf(' ') >= 0) {
            throw invalid("Expected a nonempty argument without spaces");
        }
        return value;
    }

    private static void validateWantedRef(String value) throws IOException {
        try {
            new RefId(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid wanted ref", error);
        }
        if (value.equals("HEAD")) {
            return;
        }
        if (!value.startsWith("refs/") || value.contains("//") || value.contains("..")
                || value.contains("@{") || value.endsWith("/") || value.endsWith(".")) {
            throw invalid("Expected a full wanted ref name");
        }
        for (String component : value.split("/")) {
            if (component.startsWith(".") || component.endsWith(".lock")) {
                throw invalid("Invalid wanted ref component");
            }
        }
    }

    private static long unsignedNumber(String value, long max) throws IOException {
        token(value);
        long number = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = value.charAt(i) - '0';
            if (digit < 0 || digit > 9 || number > (max - digit) / 10) {
                throw invalid("Invalid or overflowing numeric fetch argument");
            }
            number = number * 10 + digit;
        }
        return number;
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }

    private static void acceptShared(FetchRequest request, NegotiationCapabilityValue argument) throws IOException {
        String value = argument.value();
        switch (argument.cap()) {
            case SHALLOW -> request.shallowCommits().add(objectId(value));
            case DEEPEN -> {
                if (request.depth().isPresent()) {
                    throw invalid("Duplicate deepen");
                }
                int depth = (int) unsignedNumber(value, Integer.MAX_VALUE);
                if (depth == 0) {
                    throw invalid("Depth must be positive");
                }
                request.setDepth(OptionalInt.of(depth));
            }
            case DEEPEN_SINCE -> {
                if (request.deepenSince().isPresent()) {
                    throw invalid("Duplicate deepen-since");
                }
                request.setDeepenSince(OptionalLong.of(unsignedNumber(value, Long.MAX_VALUE)));
            }
            case DEEPEN_NOT -> request.deepenNot().add(token(value));
            case FILTER -> {
                if (request.filter().isPresent()) {
                    throw invalid("Duplicate filter");
                }
                request.setFilter(Optional.of(token(value)));
            }
            default -> throw invalid("Unsupported fetch argument: " + argument.cap().wireName());
        }
    }

    private static void validate(FetchRequest request) throws IOException {
        if (request.depth().isPresent() && (request.deepenSince().isPresent() || !request.deepenNot().isEmpty())) {
            throw invalid("Depth cannot be combined with deepen-since or deepen-not");
        }
        if (request.capabilities().contains(GitCapability.DEEPEN_RELATIVE.entry()) && request.depth().isEmpty()) {
            throw invalid("deepen-relative requires depth");
        }
        if (request.mode() != FetchRequest.Mode.PROTOCOL_V2 && request.waitForDone()) {
            throw invalid("wait-for-done requires protocol v2");
        }
    }
}
