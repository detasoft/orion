package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;

/**
 * Decoded negotiation message consumed individually by FetchNegotiator.
 * Initial wants, shallow boundaries, and ACK mode arrive separately in FetchRequest. END_ROUND is a
 * negotiation boundary, not the legacy flush ending the want section.
 * DONE is explicit client completion, never transport EOF. Protocol v2 still requires END_ROUND after DONE.
 */
public sealed interface NegotiationMessage {
    /** Client claim of an available object; repository lookup must establish whether it is common. */
    record Have(ObjectId objectId) implements NegotiationMessage {
        public Have {
            Objects.requireNonNull(objectId, "objectId");
        }
    }

    enum Control implements NegotiationMessage {
        END_ROUND,
        DONE
    }
}
