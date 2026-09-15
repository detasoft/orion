package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.GitReader;
import pro.deta.orion.git.parser.v2.GitWriter;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.capability.GitObjectFormat;
import pro.deta.orion.git.parser.wire.control.ControlState;
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
 * pkt-line decoder. It accepts an optional trailing LF, validates IDs and argument combinations, preserves
 * pack options, and reports malformed/truncated requests as IOException. Legacy's empty initial flush is
 * an orderly no-fetch request. V2 requires at least one want or want-ref. Unknown v2 arguments are rejected.
 * Capability advertisement, object access, ref resolution, and filter execution belong to FetchCommand;
 * parsing does not imply that an extension is enabled or that any requested object exists.
 *
 * <p>negotiate parses the request, creates the iterator, writes replies including the terminal batch,
 * and returns the accumulated context. The iterator owns common-object and readiness decisions without parsing bytes.
 * V2 feeds only already parsed initialMessages; legacy reads one message at a time and flushes replies
 * before reading more. An empty legacy request finishes without reading negotiation messages.
 * Input/output are borrowed and never closed here. Iterator processing and response framing remain
 * placeholders; request parsing and NegotiationContext are implemented, not a complete fetch exchange.
 */
public final class FetchNegotiator {
    private static final GitCapability[] V2_FLAGS = {
            GitCapability.THIN_PACK, GitCapability.OFS_DELTA, GitCapability.INCLUDE_TAG,
            GitCapability.NO_PROGRESS, GitCapability.WAIT_FOR_DONE, GitCapability.SIDEBAND_ALL,
            GitCapability.DEEPEN_RELATIVE
    };

    private final GitReader input;
    private final GitWriter output;
    private final ProtocolVersion version;

    public FetchNegotiator(GitReader input, GitWriter output, ProtocolVersion version) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.version = Objects.requireNonNull(version, "version");
    }

    public NegotiationContext negotiate() throws IOException {
        FetchRequest request = switch (version) {
            case V0, V1 -> parseLegacyRequest(input);
            case V2 -> parseV2Request(input);
        };
        FetchNegotiatorIterator iterator = new FetchNegotiatorIterator(request);
        if (version == ProtocolVersion.V2) {
            for (NegotiationMessage message : request.initialMessages()) {
                boolean more = iterator.next(message);
                writeResponses(iterator.getResponsesToSend());
                if (!more) {
                    break;
                }
            }
        } else if (!request.wants().isEmpty()) {
            boolean more;
            do {
                more = iterator.next(readNegotiationMessage(input));
                writeResponses(iterator.getResponsesToSend());
            } while (more);
        }
        return iterator.getContext();
    }

    private void writeResponses(List<NegotiationResponse> responsesToSend) throws IOException {
        if (!responsesToSend.isEmpty()) {
            output.writeNegotiationRound(responsesToSend);
            output.flush();
        }
    }

    public static NegotiationMessage readNegotiationMessage(GitReader reader) throws IOException {
        ControlState packet = reader.readPacket();
        return switch (packet) {
            case ControlState.Control.FLUSH -> NegotiationMessage.Control.END_ROUND;
            case ControlState.Control.DELIMITER, ControlState.Control.RESPONSE_END ->
                    throw invalid("Expected a data packet");
            case ControlState.Data data -> {
                String line = data.text();
                if (line.equals(GitCapability.DONE.wireName())) {
                    yield NegotiationMessage.Control.DONE;
                }
                if (line.startsWith(GitCapability.HAVE.wireName() + " ")) {
                    try {
                        yield new NegotiationMessage.Have(
                                new ObjectId(line.substring(GitCapability.HAVE.wireName().length() + 1)));
                    } catch (IllegalArgumentException error) {
                        throw new IOException("Invalid have object ID", error);
                    }
                }
                throw new IOException("Unexpected negotiation message: " + line);
            }
        };
    }

    public static FetchRequest parseV2Request(GitReader reader) throws IOException {
        FetchRequest request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        while (true) {
            ControlState packet = reader.readPacket();
            switch (packet) {
                case ControlState.Control.FLUSH -> {
                    if (request.wants().isEmpty() && request.wantRefs().isEmpty()) {
                        throw invalid("Fetch requires want or want-ref");
                    }
                    request.initialMessages().add(NegotiationMessage.Control.END_ROUND);
                    validate(request);
                    return request;
                }
                case ControlState.Control.DELIMITER, ControlState.Control.RESPONSE_END ->
                        throw invalid("Expected a data packet");
                case ControlState.Data data -> {
                    String line = data.text();
                    if (line.startsWith(GitCapability.WANT.wireName() + " ")) {
                        request.wants().add(objectId(line.substring(GitCapability.WANT.wireName().length() + 1)));
                    } else if (line.startsWith(GitCapability.HAVE.wireName() + " ")) {
                        request.initialMessages().add(new NegotiationMessage.Have(
                                objectId(line.substring(GitCapability.HAVE.wireName().length() + 1))));
                    } else if (line.equals(GitCapability.DONE.wireName())) {
                        if (request.initialMessages().contains(NegotiationMessage.Control.DONE)) {
                            throw invalid("Duplicate done");
                        }
                        request.initialMessages().add(NegotiationMessage.Control.DONE);
                    } else if (line.startsWith(GitCapability.WANT_REF.wireName() + " ")) {
                        String ref = token(line.substring(GitCapability.WANT_REF.wireName().length() + 1));
                        validateWantedRef(ref);
                        request.wantRefs().add(ref);
                    } else if (line.startsWith("packfile-uris ")) {
                        if (!request.packfileUriProtocols().isEmpty()) {
                            throw invalid("Duplicate packfile-uris");
                        }
                        for (String protocol : line.substring(14).split(",", -1)) {
                            if (!protocol.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
                                throw invalid("Invalid packfile URI protocol");
                            }
                            request.packfileUriProtocols().add(protocol);
                        }
                    } else if (isV2Flag(line)) {
                        request.capabilities().add(GitCapability.parse(line, GitObjectFormat.SHA1));
                    } else {
                        acceptShared(request, line);
                    }
                }
            }
        }
    }

    private static boolean isV2Flag(String name) {
        GitCapability capability = GitCapability.fromWireName(name).orElse(null);
        if (capability == null) {
            return false;
        }
        for (GitCapability allowed : V2_FLAGS) {
            if (capability == allowed) {
                return true;
            }
        }
        return false;
    }

    public static FetchRequest parseLegacyRequest(GitReader reader) throws IOException {
        FetchRequest request = new FetchRequest();
        boolean receivedLine = false;
        boolean wantsEnded = false;
        while (true) {
            ControlState packet = reader.readPacket();
            switch (packet) {
                case ControlState.Control.FLUSH -> {
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
                case ControlState.Control.DELIMITER, ControlState.Control.RESPONSE_END ->
                        throw invalid("Expected a data packet");
                case ControlState.Data data -> {
                    String line = data.text();
                    receivedLine = true;
                    if (line.startsWith(GitCapability.WANT.wireName() + " ")) {
                        if (wantsEnded) {
                            throw invalid("Want after legacy request options");
                        }
                        String[] values = line.substring(GitCapability.WANT.wireName().length() + 1).split(" ", -1);
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
                        acceptShared(request, line);
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

    private static void acceptShared(FetchRequest request, String line) throws IOException {
        int separator = line.indexOf(' ');
        String name = separator < 0 ? line : line.substring(0, separator);
        if (separator < 0) {
            throw invalid("Unsupported fetch argument: " + line);
        }
        String value = line.substring(separator + 1);
        GitCapability capability = GitCapability.fromWireName(name).orElse(null);
        if (capability == null) {
            throw invalid("Unsupported fetch argument: " + line);
        }
        switch (capability) {
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
            default -> throw invalid("Unsupported fetch argument: " + line);
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
