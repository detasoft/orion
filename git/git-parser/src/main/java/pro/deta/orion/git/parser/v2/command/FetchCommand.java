package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiator;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.util.Objects;

/**
 * Owns fetch negotiation, object-access checks, and the native pack producer for one fetch operation.
 * Owns FetchNegotiator over borrowed byte streams. Its version-specific methods parse FetchRequest;
 * FetchNegotiatorIterator operates on decoded messages and accumulates NegotiationContext.
 * Access checks must precede negotiation decisions and pack production; their wiring remains to be specified.
 * Owns GitStorageApi and lends it to a fresh NegotiationContext for each negotiation.
 * Context checks object presence through storage.exists; it does not authorize requested wants.
 * Graph readiness in the context remains an explicit unsupported operation until traversal and stored-delta base lookup
 * are specified. It must account for every want, resolved want-ref, and shallow boundary, not merely the
 * existence of one common object. Authorization and want-ref resolution must be wired before live use.
 * The command releases its producer on success, failure, or cancellation.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code begin(FetchRequest)} - initialize the fetch and check requested-object access.</li>
 *   <li>{@code negotiate()} - let FetchNegotiator drive the iterator and return its accumulated context.</li>
 *   <li>{@code prepareResponse()} - prepare pack production and return response metadata.</li>
 *   <li>{@code writePack(BufferedByteOutput)} - stream the producer into writer-provided output.</li>
 *   <li>{@code close()} - release command-owned production resources.</li>
 * </ul>
 * Method names and signatures are provisional; the producer must not leak into the wire-layer contract.
 */
public final class FetchCommand implements GitCommand {
    private final GitStorageApi storage;

    public FetchCommand(GitStorageApi storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public NegotiationContext negotiate(FetchNegotiator negotiator, boolean stateless) throws IOException {
        return negotiator.negotiate(new NegotiationContext(new FetchRequest(), storage), stateless);
    }

}
