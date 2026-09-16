package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.fetch.NegotiationResponse;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData.ProtocolVersion;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Serializes Git responses, including pkt-line framing, protocol-version differences, and side-band channels.
 * Provides framed byte output for pack transfer; FetchCommand owns the native pack producer and its lifetime.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code writeAdvertisement(...)} - write capabilities and refs where the protocol requires them.</li>
 *   <li>{@code writeLsRefsResponse(...)} - write the selected refs and their attributes.</li>
 *   <li>{@code writeNegotiationRound(...)} - encode the ordered replies produced by the latest negotiation step.</li>
 *   <li>{@code writeShallowInfo(...)} - write shallow and unshallow information.</li>
 *   <li>{@code beginPackResponse(...)} - write response metadata and provide framed BufferedByteOutput.</li>
 *   <li>{@code finishPackResponse()} - finish response framing without closing the transport.</li>
 *   <li>{@code writePushResponse(...)} - write unpack status and individual ref-update results.</li>
 *   <li>{@code writeError(...)} - encode a protocol error.</li>
 * </ul>
 * Constructor borrows output without writing or closing it. writeNegotiationRound preserves reply order.
 * Legacy batches may precede a round boundary and add no section headers or control markers. A nonempty
 * v2 batch represents the complete acknowledgments section, ending with DELIMITER after READY or FLUSH
 * otherwise. An empty batch writes nothing, including v2 with DONE, which omits acknowledgments.
 * GitPktLine owns packet encoding and side-band framing; the caller selects the negotiated channel.
 * flush delivers buffered bytes and does not itself encode a Git flush-pkt.
 * Other method names and signatures are provisional; native storage types must not enter this contract.
 */
public final class GitWriter {
    private final BufferedByteOutput output;

    public GitWriter(BufferedByteOutput output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    public void writeNegotiationRound(List<NegotiationResponse> responses, ProtocolVersion version,
                                     SideBand sideBand) throws IOException {
        Objects.requireNonNull(responses, "responses");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sideBand, "sideBand");
        if (responses.isEmpty()) {
            return;
        }
        if (version == ProtocolVersion.V2) {
            writeText("acknowledgments\n", sideBand);
        }
        for (NegotiationResponse response : responses) {
            String text = switch (response) {
                case NegotiationResponse.Ack ack -> "ACK " + ack.objectId().toHex() + switch (ack.status()) {
                    case PLAIN -> "\n";
                    case CONTINUE -> " continue\n";
                    case COMMON -> " common\n";
                    case READY -> " ready\n";
                };
                case NegotiationResponse.Control.NAK -> "NAK\n";
                case NegotiationResponse.Control.READY -> "ready\n";
            };
            writeText(text, sideBand);
        }
        if (version == ProtocolVersion.V2) {
            GitPktLine.Control end = responses.contains(NegotiationResponse.Control.READY)
                    ? GitPktLine.Control.DELIMITER : GitPktLine.Control.FLUSH;
            end.writeTo(output, sideBand);
        }
    }

    private void writeText(String text, SideBand sideBand) throws IOException {
        new GitPktLine.Data(text.getBytes(StandardCharsets.US_ASCII)).writeTo(output, sideBand);
    }

    public void flush() throws IOException {
        output.flush();
    }
}
