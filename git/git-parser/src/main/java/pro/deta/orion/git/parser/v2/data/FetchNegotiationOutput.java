package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;

/**
 * Ordered output events emitted by FetchNegotiator. A single input may emit zero or several events.
 * Ack, Nak, and Ready describe negotiation replies; EndRound and SendPack direct session control.
 * GitWriter owns pkt-line encoding, acknowledgment section headers, delimiters, and sideband framing.
 * These events contain no pack bytes or complete collection of objects to send.
 * Ready does not itself start output: SendPack explicitly transfers control to pack preparation.
 */
public sealed interface FetchNegotiationOutput {
    /**
     * An object acknowledgment. PLAIN has no suffix; CONTINUE, COMMON, and READY are legacy suffixes.
     * SINGLE_ACK emits PLAIN on the first common object and no duplicate final ACK after Done.
     * Protocol v2 uses only PLAIN and has a separate Ready event without an object ID.
     */
    record Ack(ObjectId objectId, Status status) implements FetchNegotiationOutput {
        public Ack {
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(status, "status");
        }
    }

    /** A protocol NAK; protocol v2 must not combine this with ACKs in the same response. */
    record Nak() implements FetchNegotiationOutput {
    }

    /** Protocol v2 readiness; followed by SendPack in the same response, never emitted with waitForDone. */
    record Ready() implements FetchNegotiationOutput {
    }

    /**
     * Completes a reply without a pack. Session supplies protocol-specific framing and decides whether
     * to read another legacy round or return from a stateless request. This is not a literal flush packet.
     */
    record EndRound() implements FetchNegotiationOutput {
    }

    /**
     * Negotiation is complete; FetchCommand may select objects and prepare pack output.
     * This event does not claim that the pack or its count is already prepared. Protocol v2 Done produces
     * no acknowledgment section; the session begins the appropriate fetch response sections instead.
     */
    record SendPack() implements FetchNegotiationOutput {
    }

    /** Suffix of a legacy ACK, with PLAIN also used by protocol v2. */
    enum Status {
        PLAIN,
        CONTINUE,
        COMMON,
        READY
    }
}
