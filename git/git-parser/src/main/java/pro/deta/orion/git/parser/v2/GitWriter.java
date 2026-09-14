package pro.deta.orion.git.parser.v2;

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
 * Method names and signatures are provisional; native storage types must not enter the writer contract.
 */
public final class GitWriter {
}
