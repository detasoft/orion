package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Immutable parsed fetch arguments, independent of packet framing and repository access.
 * Legacy capabilities come from the first want; protocol v2 flags are separate argument lines. Pack flags
 * remain available to the eventual producer instead of being discarded during negotiation parsing.
 * Initial messages are empty for legacy, whose haves follow the want-section flush. For protocol v2 they
 * preserve the already consumed have/done order and include the final END_ROUND, so iteration never reads
 * into the next request. An empty legacy want section represents a client ending without requesting a pack.
 *
 * <p>Depth, time/ref exclusions, filter expression, requested refs, and URI protocols are preserved for the
 * fetch command. Parsing validates their syntax and incompatible combinations, not repository existence,
 * access, advertised capability selection, filter execution, or ref/revision resolution. Those checks must
 * precede pack production. All collections are snapshots. Current object IDs require SHA-1.
 */
public record FetchRequest(Set<ObjectId> wants, Set<ObjectId> shallowCommits, Mode mode,
                           Set<String> capabilities, List<NegotiationMessage> initialMessages,
                           Set<String> wantRefs, OptionalInt depth, OptionalLong deepenSince,
                           Set<String> deepenNot, Optional<String> filter, Set<String> packfileUriProtocols) {
    public FetchRequest {
        wants = Set.copyOf(wants);
        shallowCommits = Set.copyOf(shallowCommits);
        Objects.requireNonNull(mode, "mode");
        capabilities = Set.copyOf(capabilities);
        initialMessages = List.copyOf(initialMessages);
        wantRefs = Set.copyOf(wantRefs);
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(deepenSince, "deepenSince");
        deepenNot = Set.copyOf(deepenNot);
        filter = Objects.requireNonNull(filter, "filter");
        packfileUriProtocols = Set.copyOf(packfileUriProtocols);
    }

    public boolean waitForDone() {
        return capabilities.contains(GitCapability.WAIT_FOR_DONE.wireToken());
    }

    /** Negotiated legacy ACK behavior, or the protocol v2 acknowledgment-section rules. */
    public enum Mode {
        SINGLE_ACK,
        MULTI_ACK,
        MULTI_ACK_DETAILED,
        PROTOCOL_V2
    }
}
