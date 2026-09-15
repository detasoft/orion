package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.FetchRequest;

import java.io.IOException;
import java.util.Objects;

/**
 * Object-based negotiation iterator owned by FetchNegotiator. It starts with the parsed request and owns
 * the accumulating NegotiationContext. Protocol v2 consumes request.initialMessages without reading another
 * request; legacy obtains subsequent messages from input. Tests can supply object messages directly.
 * hasNext/next will yield ordered responses while updating common objects, readiness, and client completion.
 * Repeated hasNext must not duplicate responses; next after exhaustion must fail with NoSuchElementException.
 * Readiness, repository lookup, and ACK decisions remain unimplemented. No empty successful result is faked.
 * getContext always returns the same context, including after exhaustion. No byte parsing or stream ownership.
 */
public final class FetchNegotiatorIterator {
    private final NegotiationContext context;
    private final MessageSource input;

    public FetchNegotiatorIterator(FetchRequest request, MessageSource input) {
        this.context = new NegotiationContext(request);
        this.input = Objects.requireNonNull(input, "input");
    }

    public boolean hasNext() throws IOException {
        throw new UnsupportedOperationException("Negotiation iteration is not implemented");
    }

    public NegotiationResponse next() throws IOException {
        throw new UnsupportedOperationException("Negotiation iteration is not implemented");
    }

    public NegotiationContext getContext() {
        return context;
    }

    /** Supplies a decoded message; EOF is an error rather than implicit DONE. */
    @FunctionalInterface
    public interface MessageSource {
        NegotiationMessage read() throws IOException;
    }
}
