package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;

/**
 * Decoded negotiation reply. GitWriter owns packet encoding and protocol-specific response framing.
 * Responses can be recorded as values in algorithm tests; flushing is a separate Output operation.
 * Decisions to continue or prepare a pack are FetchNegotiator.RoundResult values, not wire replies.
 */
public sealed interface NegotiationResponse {
    /** Object acknowledgment; protocol v2 uses PLAIN, while legacy may use a negotiated suffix. */
    record Ack(ObjectId objectId, Status status) implements NegotiationResponse {
        public Ack {
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(status, "status");
        }
    }

    /** NAK has no object ID; READY is the bare protocol v2 reply and differs from a legacy ACK ready. */
    enum Control implements NegotiationResponse {
        NAK,
        READY
    }

    /** ACK suffix, or PLAIN for an acknowledgment without a suffix. */
    enum Status {
        PLAIN,
        CONTINUE,
        COMMON,
        READY
    }
}
