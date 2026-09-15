package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;
import java.util.Set;

/**
 * Decoded input events for one fetch negotiation, without pkt-line or storage dependencies.
 * Start precedes all other events and contains validated, authorized wants and client shallow boundaries.
 * Have events arrive individually; an unknown object is not an error or an acknowledged common object.
 * EndRound represents the negotiation round boundary, not the legacy flush terminating the want section.
 * Done means the client has finished negotiating, not transport EOF. No common objects are required.
 * Legacy Done ends negotiation immediately. In protocol v2, the request still ends with EndRound; no pack
 * response is emitted before that boundary. Illegal event order must fail rather than start pack production.
 * Further fetch options such as depth and object filtering remain with FetchCommand; these events do not
 * describe pack selection. This contract currently covers explicit object wants, not ref-in-want resolution.
 */
public sealed interface FetchNegotiationInput {
    /**
     * Initializes negotiation. Sets are immutable snapshots; accept must reject empty wants.
     * shallowCommits limit assumptions about client history: parents beyond these boundaries are not implied.
     * mode is selected from the actual wire protocol and negotiated capabilities, not the Java package name.
     * waitForDone is the protocol v2 option preventing early readiness; accept must reject it in legacy modes.
     * The caller normalizes ref wants into authorized object IDs before supplying this event.
     */
    record Start(Set<ObjectId> wants, Set<ObjectId> shallowCommits, Mode mode, boolean waitForDone)
            implements FetchNegotiationInput {
        public Start {
            wants = Set.copyOf(wants);
            shallowCommits = Set.copyOf(shallowCommits);
            Objects.requireNonNull(mode, "mode");
        }
    }

    /**
     * A client claim of local object availability. It becomes common only after repository lookup succeeds.
     * Repeated claims must not create duplicate stored state; response rules depend on the protocol mode.
     */
    record Have(ObjectId objectId) implements FetchNegotiationInput {
        public Have {
            Objects.requireNonNull(objectId, "objectId");
        }
    }

    /** Marks the end of the current batch; it is distinct from the client's decision to finish negotiation. */
    record EndRound() implements FetchNegotiationInput {
    }

    /** Requests completion using the information received so far, including clone with no haves. */
    record Done() implements FetchNegotiationInput {
    }

    /** Selects legacy ACK semantics or the independent protocol v2 acknowledgment section semantics. */
    enum Mode {
        SINGLE_ACK,
        MULTI_ACK,
        MULTI_ACK_DETAILED,
        PROTOCOL_V2
    }
}
