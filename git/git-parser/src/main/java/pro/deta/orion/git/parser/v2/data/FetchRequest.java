package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Mutable parsed fetch request, filled directly by FetchNegotiator without an intermediate builder.
 * Collection accessors expose this request's mutable collections; scalar setters replace optional values.
 * Known capabilities use GitCapability.Entry, retaining values and custom names without string flags.
 * Parsing and cross-argument validation belong to FetchNegotiator, not this data object.
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
