package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.FetchNegotiationInput;
import pro.deta.orion.git.parser.v2.data.FetchNegotiationOutput;

import java.io.IOException;

/**
 * Negotiates common objects for a fetch owned by FetchCommand, independently of transport and pack writing.
 * accept consumes one decoded input event and synchronously emits ordered output events through Output.
 * It must not retain the callback or read further wire input. Output failure aborts the exchange, propagates
 * to the caller, and prevents further emissions or reuse of this negotiation. Calls are sequential.
 *
 * <p>Planned state consists of Start parameters, confirmed common objects, and protocol progress. A legacy
 * stateful exchange keeps that state across rounds. A protocol v2 or stateless HTTP request starts fresh;
 * it cannot depend on state retained from an earlier request. Client-supplied context reconstructs the round.
 * Repository lookup and readiness traversal remain to be specified; this class currently has no storage wiring.
 * Presence checks should use indexed metadata rather than inflate object content. Readiness traversal must
 * respect client shallow boundaries and must not collect all reachable trees and blobs on each round.
 * Unknown haves do not become common. Common objects are roots for later client-availability analysis, not
 * proof that all their ancestors exist at a shallow client. Pack selection and delta-base choice are separate.
 *
 * <p>SINGLE_ACK confirms the first common object immediately and then stays silent through subsequent legacy
 * flushes; after Done it emits SendPack without repeating that ACK. With no common object, Done emits NAK
 * before SendPack. Multi-ACK modes retain their distinct continue/common/ready and final acknowledgment rules.
 * Protocol v2 buffers acknowledgment decisions until EndRound, omits acknowledgments when Done was supplied,
 * and sends Ready only together with SendPack and only when waitForDone permits it. Ending a response without
 * a pack emits EndRound. Never treat the legacy want-section flush as a negotiation round.
 *
 * <p>Preliminary method: accept(input, output). It will validate ordering, update negotiation, and emit replies.
 * Input/output contracts are the only implemented part of this scaffold; the method remains a placeholder.
 * No parser, graph traversal, pack producer, or connection resource is owned by this class yet.
 */
public final class FetchNegotiator {
    public void accept(FetchNegotiationInput input, Output output) throws IOException {
        throw new UnsupportedOperationException("Fetch negotiation is not implemented");
    }

    /**
     * Synchronous output consumer, normally implemented by session orchestration using GitWriter.
     * Receives events individually without an intermediate response list. IOException stops negotiation;
     * already delivered events cannot be retracted or replayed automatically.
     */
    @FunctionalInterface
    public interface Output {
        void emit(FetchNegotiationOutput output) throws IOException;
    }
}
