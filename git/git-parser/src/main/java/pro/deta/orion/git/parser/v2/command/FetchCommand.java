package pro.deta.orion.git.parser.v2.command;

/**
 * Owns fetch negotiation, object-access checks, and the native pack producer for one fetch operation.
 * Owns FetchNegotiator, which reads negotiation messages and writes replies through borrowed
 * BufferedByteInput and BufferedByteOutput. The session selects the protocol flow before delegating fetch.
 * The command releases its producer on success, failure, or cancellation.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code begin(FetchRequest)} - initialize the fetch and check requested-object access.</li>
 *   <li>{@code negotiate()} - delegate the exchange to the stream-based FetchNegotiator.</li>
 *   <li>{@code prepareResponse()} - prepare pack production and return response metadata.</li>
 *   <li>{@code writePack(BufferedByteOutput)} - stream the producer into writer-provided output.</li>
 *   <li>{@code close()} - release command-owned production resources.</li>
 * </ul>
 * Method names and signatures are provisional; the producer must not leak into the wire-layer contract.
 */
public final class FetchCommand {
}
