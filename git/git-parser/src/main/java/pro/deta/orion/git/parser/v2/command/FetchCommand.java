package pro.deta.orion.git.parser.v2.command;

/**
 * Owns fetch negotiation, object-access checks, and the native pack producer for one fetch operation.
 * Receives the parsed data.FetchRequest from GitReader, checks access, and owns FetchNegotiator constructed
 * with that request and typed input/output boundaries. GitReader/GitWriter adapt buffered byte streams;
 * the negotiation algorithm sees request/message/response objects only. The session selects the protocol flow.
 * The command releases its producer on success, failure, or cancellation.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code begin(FetchRequest)} - initialize the fetch and check requested-object access.</li>
 *   <li>{@code negotiate()} - let FetchNegotiator drive its rounds and return the terminal decision.</li>
 *   <li>{@code prepareResponse()} - prepare pack production and return response metadata.</li>
 *   <li>{@code writePack(BufferedByteOutput)} - stream the producer into writer-provided output.</li>
 *   <li>{@code close()} - release command-owned production resources.</li>
 * </ul>
 * Method names and signatures are provisional; the producer must not leak into the wire-layer contract.
 */
public final class FetchCommand {
}
