package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;
import java.util.Set;

/**
 * Parsed initial fetch data, independent of pkt-line encoding, streams, and the negotiation algorithm.
 * GitReader builds this value; FetchCommand checks requested-object access before starting negotiation.
 * wants identifies requested objects. shallowCommits identifies client history boundaries beyond which
 * parent availability cannot be inferred. Sets are immutable snapshots; haves arrive as separate messages.
 * mode describes actual wire semantics, not the Java package version. waitForDone is a protocol v2 option
 * that prevents early readiness; it is invalid in legacy modes. Protocol setup validation remains pending.
 * This preliminary request covers negotiation context only. Pack options, depth/filter requests, and
 * ref-in-want normalization remain to be specified; no packet parsing occurs in this data type.
 */
public record FetchRequest(Set<ObjectId> wants, Set<ObjectId> shallowCommits,
                           Mode mode, boolean waitForDone) {
    public FetchRequest {
        wants = Set.copyOf(wants);
        shallowCommits = Set.copyOf(shallowCommits);
        Objects.requireNonNull(mode, "mode");
    }

    /** Negotiated ACK behavior for legacy exchanges, or the protocol v2 response-section rules. */
    public enum Mode {
        SINGLE_ACK,
        MULTI_ACK,
        MULTI_ACK_DETAILED,
        PROTOCOL_V2
    }
}
