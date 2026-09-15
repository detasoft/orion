package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.data.NegotiationResponse;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiator;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.Objects;

/**
 * Serializes Git responses, including pkt-line framing, protocol-version differences, and side-band channels.
 * Provides framed byte output for pack transfer; FetchCommand owns the native pack producer and its lifetime.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code writeAdvertisement(...)} - write capabilities and refs where the protocol requires them.</li>
 *   <li>{@code writeLsRefsResponse(...)} - write the selected refs and their attributes.</li>
 *   <li>{@code writeNegotiationResponse(...)} - encode ACK/NAK and negotiation status.</li>
 *   <li>{@code writeShallowInfo(...)} - write shallow and unshallow information.</li>
 *   <li>{@code beginPackResponse(...)} - write response metadata and provide framed BufferedByteOutput.</li>
 *   <li>{@code finishPackResponse()} - finish response framing without closing the transport.</li>
 *   <li>{@code writePushResponse(...)} - write unpack status and individual ref-update results.</li>
 *   <li>{@code writeError(...)} - encode a protocol error.</li>
 * </ul>
 * Constructor borrows output without writing or closing it. writeNegotiationResponse implements the typed
 * negotiation output boundary; encoding and protocol framing remain placeholders. flush delivers buffered
 * bytes and does not itself encode a Git flush-pkt. Protocol response framing remains to be specified.
 * Other method names and signatures are provisional; native storage types must not enter this contract.
 */
public final class GitWriter implements FetchNegotiator.Output {
    private final BufferedByteOutput output;

    public GitWriter(BufferedByteOutput output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void writeNegotiationResponse(NegotiationResponse response) throws IOException {
        throw new UnsupportedOperationException("Negotiation response encoding is not implemented");
    }

    @Override
    public void flush() throws IOException {
        throw new UnsupportedOperationException("Negotiation output flushing is not implemented");
    }
}
