package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.GitReader;
import pro.deta.orion.git.parser.v2.fetch.NegotiationCapability;
import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.wire.capability.GitObjectFormat;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

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
 * Mutable fetch request with static wire parsers, without an intermediate builder.
 * Collection accessors expose this request's mutable collections; scalar setters replace optional values.
 * Known capabilities use GitCapability.Entry, retaining values and custom names without string flags.
 * parseLegacy reads the v0/v1 want section through its flush without consuming negotiation haves.
 * parseV2 starts after the command-header delimiter and reads arguments, including have/done, through flush.
 * Both create a fresh request, validate syntax and argument combinations, and leave the borrowed input open.
 * Malformed or truncated requests fail with IOException. Capability advertisement and object access checks
 * belong to command preparation; successful parsing does not imply that a requested feature is supported.
 *
 * <p>Legacy capabilities come from the first want. V2 initialMessages preserve parsed have/done order and
 * the final END_ROUND; legacy leaves this list empty and reads subsequent negotiation messages separately.
 * An empty legacy want section ends the exchange. Pack options and shallow boundaries remain available to
 * FetchCommand. Each parse creates its own request. Finish population before negotiation and do not mutate
 * the request concurrently or while its context is in use. Current object IDs require SHA-1.
 */
public final class FetchRequest {
    private final Set<ObjectId> wants = new LinkedHashSet<>();
    private final Set<ObjectId> shallowCommits = new LinkedHashSet<>();
    private final Set<GitCapability.Entry> capabilities = new LinkedHashSet<>();
    private final List<NegotiationMessage> initialMessages = new ArrayList<>();
    private final Set<String> wantRefs = new LinkedHashSet<>();
    private final Set<String> deepenNot = new LinkedHashSet<>();
    private final Set<String> packfileUriProtocols = new LinkedHashSet<>();
    private Mode mode = Mode.SINGLE_ACK;
    private OptionalInt depth = OptionalInt.empty();
    private OptionalLong deepenSince = OptionalLong.empty();
    private Optional<String> filter = Optional.empty();

    public static FetchRequest parseV2(GitReader reader) throws IOException {
        var request = new FetchRequest();
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
                    NegotiationCapability argument = NegotiationCapability.parse(data.text());
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

    public static FetchRequest parseLegacy(GitReader reader) throws IOException {
        var request = new FetchRequest();
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
                    NegotiationCapability argument = NegotiationCapability.parse(data.text());
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

    private static void acceptShared(FetchRequest request, NegotiationCapability argument) throws IOException {
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
    public Set<ObjectId> wants() {
        return wants;
    }

    public Set<ObjectId> shallowCommits() {
        return shallowCommits;
    }

    public Set<GitCapability.Entry> capabilities() {
        return capabilities;
    }

    public List<NegotiationMessage> initialMessages() {
        return initialMessages;
    }

    public Set<String> wantRefs() {
        return wantRefs;
    }

    public Set<String> deepenNot() {
        return deepenNot;
    }

    public Set<String> packfileUriProtocols() {
        return packfileUriProtocols;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public OptionalInt depth() {
        return depth;
    }

    public void setDepth(OptionalInt depth) {
        this.depth = Objects.requireNonNull(depth, "depth");
    }

    public OptionalLong deepenSince() {
        return deepenSince;
    }

    public void setDeepenSince(OptionalLong deepenSince) {
        this.deepenSince = Objects.requireNonNull(deepenSince, "deepenSince");
    }

    public Optional<String> filter() {
        return filter;
    }

    public void setFilter(Optional<String> filter) {
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    public boolean waitForDone() {
        return capabilities.contains(GitCapability.WAIT_FOR_DONE.entry());
    }

    /** Negotiated legacy ACK behavior, or the protocol v2 acknowledgment-section rules. */
    public enum Mode {
        SINGLE_ACK,
        MULTI_ACK,
        MULTI_ACK_DETAILED,
        PROTOCOL_V2
    }
}
