package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.FetchRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Object-based negotiation state machine owned by FetchNegotiator. Each next(message) processes one decoded
 * message and replaces the pending reply batch; true means more messages are needed, false ends the exchange.
 * Replies from the terminal step must still be sent. Calls after completion must fail with IllegalStateException.
 * Tests feed messages directly and inspect replies and the accumulating context after each step or round.
 * The caller supplies v2 initialMessages, including END_ROUND after DONE, without reading another request.
 * Legacy replies can be delivered before the round ends, avoiding a wait for input from a client awaiting ACK.
 * getResponsesToSend returns an immutable snapshot of the latest step, without draining it; repeated reads
 * must not change state. The next step must clear previous replies even if it produces no new replies.
 * Readiness, repository lookup, and ACK decisions remain unimplemented. No empty successful result is faked.
 * getContext always returns the same context. No byte parsing, input callbacks, or stream ownership.
 */
public final class FetchNegotiatorIterator {
    private final NegotiationContext context;
    private final List<NegotiationResponse> responsesToSend = new ArrayList<>();

    public FetchNegotiatorIterator(FetchRequest request) {
        this.context = new NegotiationContext(request);
    }

    public boolean next(NegotiationMessage msg) throws IOException {
        throw new UnsupportedOperationException("Negotiation iteration is not implemented");
    }

    public NegotiationContext getContext() {
        return context;
    }

    public List<NegotiationResponse> getResponsesToSend() {
        return List.copyOf(responsesToSend);
    }
}
