package pro.deta.orion.git.parser.v2.command;

/**
 * Owns fetch negotiation, object-access checks, and the native pack producer for one fetch operation.
 * Retains haves, common haves, and readiness state; the session controls wire exchange order and the writer
 * encodes responses. The command releases its producer on success, failure, or cancellation.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code begin(FetchRequest)} - initialize the fetch and check requested-object access.</li>
 *   <li>{@code negotiate(NegotiationMessage)} - update negotiation and return the next response.</li>
 *   <li>{@code prepareResponse()} - prepare pack production and return response metadata.</li>
 *   <li>{@code writePack(BufferedByteOutput)} - stream the producer into writer-provided output.</li>
 *   <li>{@code close()} - release command-owned production resources.</li>
 * </ul>
 * Method names and signatures are provisional; the producer must not leak into the wire-layer contract.
 */
public final class FetchCommand {
}
