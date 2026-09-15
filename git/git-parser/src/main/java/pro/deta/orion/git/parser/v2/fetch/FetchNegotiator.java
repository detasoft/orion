package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.GitReader;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData.ProtocolVersion;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Owns fetch request parsing and the wire loop around an object-based FetchNegotiatorIterator.
 * parseV0Request and parseV1Request share the legacy want-section grammar and stop at its flush without
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
 * <p>negotiate parses the request, creates the iterator, writes each yielded response, and returns its
 * accumulated context. The iterator owns common-object and readiness decisions without parsing bytes.
 * Input/output are borrowed and never closed here. Iterator processing and response framing remain
 * placeholders; request parsing and NegotiationContext are implemented, not a complete fetch exchange.
 */
public final class FetchNegotiator {
    private static final Set<String> V2_FLAGS = Set.of(
            GitCapability.THIN_PACK.wireToken(), GitCapability.OFS_DELTA.wireToken(),
            GitCapability.INCLUDE_TAG.wireToken(), GitCapability.NO_PROGRESS.wireToken(),
            GitCapability.WAIT_FOR_DONE.wireToken(), GitCapability.SIDEBAND_ALL.wireToken(),
            GitCapability.DEEPEN_RELATIVE.wireToken());

    private final BufferedByteInput input;
    private final BufferedByteOutput output;
    private final ProtocolVersion version;

    public FetchNegotiator(BufferedByteInput input, BufferedByteOutput output, ProtocolVersion version) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.version = Objects.requireNonNull(version, "version");
    }

    public NegotiationContext negotiate() throws IOException {
        FetchRequest request = switch (version) {
            case V0 -> parseV0Request(input);
            case V1 -> parseV1Request(input);
            case V2 -> parseV2Request(input);
        };
        FetchNegotiatorIterator iterator = new FetchNegotiatorIterator(request,
                new GitReader(input)::readNegotiationMessage);
        while (iterator.hasNext()) {
            NegotiationResponse response = iterator.next();
            response.writeTo(output);
        }
        return iterator.getContext();
    }

    public static FetchRequest parseV0Request(BufferedByteInput input) throws IOException {
        return parseLegacyRequest(input);
    }

    public static FetchRequest parseV1Request(BufferedByteInput input) throws IOException {
        return parseLegacyRequest(input);
    }

    public static FetchRequest parseV2Request(BufferedByteInput input) throws IOException {
        GitReader reader = new GitReader(input);
        Arguments arguments = new Arguments();
        while (true) {
            ControlState packet = reader.readControlState();
            if (packet.type() == ControlState.ControlType.FLUSH) {
                if (arguments.wants.isEmpty() && arguments.wantRefs.isEmpty()) {
                    throw invalid("Fetch requires want or want-ref");
                }
                arguments.messages.add(NegotiationMessage.Control.END_ROUND);
                return arguments.build(FetchRequest.Mode.PROTOCOL_V2);
            }
            String line = reader.readText(packet);
            if (line.startsWith("want ")) {
                arguments.wants.add(objectId(line.substring(5)));
            } else if (line.startsWith("have ")) {
                arguments.messages.add(new NegotiationMessage.Have(objectId(line.substring(5))));
            } else if (line.equals("done")) {
                if (arguments.messages.contains(NegotiationMessage.Control.DONE)) {
                    throw invalid("Duplicate done");
                }
                arguments.messages.add(NegotiationMessage.Control.DONE);
            } else if (line.startsWith("want-ref ")) {
                String ref = token(line.substring(9));
                validateWantedRef(ref);
                arguments.wantRefs.add(ref);
            } else if (line.startsWith("packfile-uris ")) {
                if (!arguments.uriProtocols.isEmpty()) {
                    throw invalid("Duplicate packfile-uris");
                }
                for (String protocol : line.substring(14).split(",", -1)) {
                    if (!protocol.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
                        throw invalid("Invalid packfile URI protocol");
                    }
                    arguments.uriProtocols.add(protocol);
                }
            } else if (V2_FLAGS.contains(line)) {
                arguments.capabilities.add(line);
            } else {
                arguments.acceptShared(line);
            }
        }
    }

    private static FetchRequest parseLegacyRequest(BufferedByteInput input) throws IOException {
        GitReader reader = new GitReader(input);
        Arguments arguments = new Arguments();
        boolean receivedLine = false;
        boolean wantsEnded = false;
        while (true) {
            ControlState packet = reader.readControlState();
            if (packet.type() == ControlState.ControlType.FLUSH) {
                if (receivedLine && arguments.wants.isEmpty()) {
                    throw invalid("Legacy fetch requires want");
                }
                FetchRequest.Mode mode = arguments.capabilities.contains(GitCapability.MULTI_ACK_DETAILED.wireToken())
                        ? FetchRequest.Mode.MULTI_ACK_DETAILED
                        : arguments.capabilities.contains(GitCapability.MULTI_ACK.wireToken())
                                ? FetchRequest.Mode.MULTI_ACK : FetchRequest.Mode.SINGLE_ACK;
                return arguments.build(mode);
            }
            String line = reader.readText(packet);
            receivedLine = true;
            if (line.startsWith("want ")) {
                if (wantsEnded) {
                    throw invalid("Want after legacy request options");
                }
                String[] values = line.substring(5).split(" ", -1);
                boolean firstWant = arguments.wants.isEmpty();
                if (!firstWant && values.length != 1) {
                    throw invalid("Capabilities are only allowed on the first want");
                }
                arguments.wants.add(objectId(values[0]));
                for (int i = 1; i < values.length; i++) {
                    String capability = token(values[i]);
                    if (capability.startsWith("object-format=") && !capability.equals("object-format=sha1")) {
                        throw invalid("Only SHA-1 object IDs are supported");
                    }
                    arguments.capabilities.add(capability);
                }
            } else {
                if (arguments.wants.isEmpty()) {
                    throw invalid("Legacy request must start with want");
                }
                wantsEnded = true;
                arguments.acceptShared(line);
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

    /** Local parser accumulator; it owns no streams, repository state, or negotiation decisions. */
    private static final class Arguments {
        private final Set<ObjectId> wants = new LinkedHashSet<>();
        private final Set<ObjectId> shallow = new LinkedHashSet<>();
        private final Set<String> capabilities = new LinkedHashSet<>();
        private final List<NegotiationMessage> messages = new ArrayList<>();
        private final Set<String> wantRefs = new LinkedHashSet<>();
        private final Set<String> deepenNot = new LinkedHashSet<>();
        private final Set<String> uriProtocols = new LinkedHashSet<>();
        private OptionalInt depth = OptionalInt.empty();
        private OptionalLong deepenSince = OptionalLong.empty();
        private Optional<String> filter = Optional.empty();

        private void acceptShared(String line) throws IOException {
            if (line.startsWith("shallow ")) {
                shallow.add(objectId(line.substring(8)));
            } else if (line.startsWith("deepen ")) {
                if (depth.isPresent()) {
                    throw invalid("Duplicate deepen");
                }
                int value = (int) unsignedNumber(line.substring(7), Integer.MAX_VALUE);
                if (value == 0) {
                    throw invalid("Depth must be positive");
                }
                depth = OptionalInt.of(value);
            } else if (line.startsWith("deepen-since ")) {
                if (deepenSince.isPresent()) {
                    throw invalid("Duplicate deepen-since");
                }
                deepenSince = OptionalLong.of(unsignedNumber(line.substring(13), Long.MAX_VALUE));
            } else if (line.startsWith("deepen-not ")) {
                deepenNot.add(token(line.substring(11)));
            } else if (line.startsWith("filter ")) {
                if (filter.isPresent()) {
                    throw invalid("Duplicate filter");
                }
                filter = Optional.of(token(line.substring(7)));
            } else {
                throw invalid("Unsupported fetch argument: " + line);
            }
        }

        private FetchRequest build(FetchRequest.Mode mode) throws IOException {
            if (depth.isPresent() && (deepenSince.isPresent() || !deepenNot.isEmpty())) {
                throw invalid("Depth cannot be combined with deepen-since or deepen-not");
            }
            if (capabilities.contains(GitCapability.DEEPEN_RELATIVE.wireToken()) && depth.isEmpty()) {
                throw invalid("deepen-relative requires depth");
            }
            if (mode != FetchRequest.Mode.PROTOCOL_V2 && capabilities.contains(GitCapability.WAIT_FOR_DONE.wireToken())) {
                throw invalid("wait-for-done requires protocol v2");
            }
            return new FetchRequest(wants, shallow, mode, capabilities, messages, wantRefs,
                    depth, deepenSince, deepenNot, filter, uriProtocols);
        }
    }
}
