package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.Objects;

/**
 * Performs the fetch negotiation exchange through borrowed BufferedByteInput and BufferedByteOutput.
 * FetchCommand owns this negotiator. The constructor stores the streams without reading or writing;
 * negotiate will alternate reading client negotiation messages and writing protocol-appropriate replies.
 * Input must be positioned at the negotiation section selected by the caller, after protocol bootstrap.
 * Protocol configuration, repository access, and the result used for pack selection remain to be specified.
 * The current signature is a scaffold, not a complete protocol configuration or negotiation implementation.
 *
 * <p>Consume messages incrementally and flush replies before waiting for a client response when required.
 * Distinguish the legacy want-section flush, negotiation round boundaries, and done; transport EOF is not
 * successful completion. Reuse Git pkt-line framing rather than introduce another packet codec.
 * Do not read past the applicable exchange boundary. Prefetched bytes remain in the same borrowed input
 * for the caller. Neither stream is closed here, including on failure. IOException aborts the exchange;
 * already written replies cannot be retracted or replayed automatically. Calls are sequential.
 *
 * <p>Legacy stateful negotiation retains common objects across rounds. Protocol v2 and stateless HTTP must
 * not depend on state from an earlier request. Single-ACK, multi-ACK, and protocol v2 have different reply
 * rules; in particular, single-ACK confirms the first common object immediately and does not repeat it on
 * done. Protocol v2 consumes the complete request before replying and omits acknowledgments after done.
 * Common-object checks should use indexed metadata. Client shallow boundaries constrain reachability;
 * negotiation must not assume the client has parents beyond those boundaries or load every blob to check ready.
 *
 * <p>Preliminary method: negotiate() reads negotiation input and writes replies. Pack selection and pack
 * streaming remain with FetchCommand. Repository lookup, graph traversal, and negotiation are not implemented.
 */
public final class FetchNegotiator {
    private final BufferedByteInput input;
    private final BufferedByteOutput output;

    public FetchNegotiator(BufferedByteInput input, BufferedByteOutput output) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
    }

    public void negotiate() throws IOException {
        throw new UnsupportedOperationException("Fetch negotiation is not implemented");
    }
}
