package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.data.NegotiationMessage;
import pro.deta.orion.git.parser.v2.data.NegotiationResponse;

import java.io.IOException;
import java.util.Objects;

/**
 * Drives fetch negotiation for FetchCommand through typed Input and Output boundaries.
 * FetchRequest contains already parsed initial wants and negotiation context. Input supplies decoded round
 * messages; Output accepts decoded replies. FetchCommand supplies these boundaries after request parsing and
 * access checks. This algorithm has no dependency on buffered byte streams, packet codecs, or concrete readers.
 * The constructor stores borrowed boundaries without I/O. It closes neither boundary. Repository lookup and
 * request validation remain pending. The algorithm retains negotiation state, not packet parsing state.
 *
 * <p>negotiate drives negotiateRound until CONTINUE is no longer returned. negotiateRound consumes messages
 * through one round boundary or legacy DONE, writes the required replies, and flushes before returning or
 * waiting for client input when required. It does not consume the next round. Legacy replies can be emitted
 * for individual haves; protocol v2 replies follow the complete request, including END_ROUND after DONE.
 * Input exposes the negotiation section after initial request parsing; the want-section flush is not a round.
 * Unexpected EOF, malformed messages, and I/O failure abort the exchange. Output cannot be retracted or replayed.
 *
 * <p>Common objects and ACK progress belong to this instance across legacy stateful rounds. Protocol v2 and
 * stateless HTTP cannot rely on state from an earlier request. Single-ACK confirms the first common object
 * immediately and does not repeat that ACK after DONE. Protocol v2 omits acknowledgments after DONE.
 * Presence checks should use indexed metadata. Client shallow boundaries constrain inferred availability;
 * parents beyond those boundaries are not implied. Readiness should not load all reachable trees and blobs.
 *
 * <p>Tests call negotiateRound with a queue of NegotiationMessage objects and an Output recording
 * NegotiationResponse objects and flush calls. Assert consumed messages, ordered replies, and RoundResult
 * after each round, then reuse the same instance to exercise accumulated state. Unread messages for later
 * rounds must remain queued. Interactive test inputs can require an ACK before supplying more messages.
 * Byte encoding/decoding belongs in separate GitReader/GitWriter tests. No alternative negotiation algorithm
 * or test-only subclass is needed.
 *
 * <p>negotiate may resume after individually processed nonterminal rounds. A terminal result forbids further
 * rounds on this instance. It never starts pack output; FetchCommand owns pack selection and streaming.
 * The orchestration loop is present. Round processing, request validation, repository lookup,
 * and graph traversal are placeholders; this scaffold cannot perform a real fetch yet.
 */
public final class FetchNegotiator {
    private final FetchRequest request;
    private final Input input;
    private final Output output;

    public FetchNegotiator(FetchRequest request, Input input, Output output) {
        this.request = Objects.requireNonNull(request, "request");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
    }

    public RoundResult negotiate() throws IOException {
        RoundResult result;
        do {
            result = negotiateRound();
        } while (result == RoundResult.CONTINUE);
        return result;
    }

    public RoundResult negotiateRound() throws IOException {
        throw new UnsupportedOperationException("Fetch negotiation round is not implemented");
    }

    /**
     * Supplies one decoded, nonnull message at a time. Unexpected end of input is IOException, not DONE.
     * Implementations include GitReader over buffered bytes and object queues in tests.
     */
    @FunctionalInterface
    public interface Input {
        NegotiationMessage readNegotiationMessage() throws IOException;
    }

    /**
     * Receives ordered replies and explicit transport flush requests. flush is not a Git protocol packet.
     * Implementations include GitWriter and object collectors in tests. Failures abort negotiation.
     */
    public interface Output {
        void writeNegotiationResponse(NegotiationResponse response) throws IOException;

        void flush() throws IOException;
    }

    /**
     * CONTINUE requires another round on this exchange. END_REQUEST completes a response without a pack,
     * including a stateless round requiring a new client request; it must not wait for that request here.
     * SEND_PACK completes negotiation and lets FetchCommand prepare the pack. Neither terminal outcome
     * closes the borrowed streams. I/O or malformed-protocol failures are exceptions, not round results.
     */
    public enum RoundResult {
        CONTINUE,
        END_REQUEST,
        SEND_PACK
    }
}
