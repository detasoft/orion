package pro.deta.orion.git.parser.v2.command;

/**
 * Owns fetch negotiation, object-access checks, and the native pack producer for one fetch operation.
 * Owns FetchNegotiator, which retains common objects and negotiation progress. The session controls wire
 * exchange order and consumes FetchNegotiationOutput events; the writer encodes replies.
 * The command releases its producer on success, failure, or cancellation.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code begin(FetchRequest)} - initialize the fetch and check requested-object access.</li>
 *   <li>{@code negotiate(FetchNegotiationInput, FetchNegotiator.Output)} - delegate one event and emit replies.</li>
 *   <li>{@code prepareResponse()} - prepare pack production and return response metadata.</li>
 *   <li>{@code writePack(BufferedByteOutput)} - stream the producer into writer-provided output.</li>
 *   <li>{@code close()} - release command-owned production resources.</li>
 * </ul>
 * Method names and signatures are provisional; the producer must not leak into the wire-layer contract.
 */
public final class FetchCommand {
}
