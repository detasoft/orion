package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;

/**
 * Decoded negotiation reply. GitWriter owns packet encoding and protocol-specific response framing.
 * Responses can be recorded as values in algorithm tests; transport flushing is separate from protocol packets.
 * The iterator exposes the latest step's replies in wire order and accumulates common objects in
 * NegotiationContext. FetchNegotiator passes reply batches to GitWriter, including the terminal batch.
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
