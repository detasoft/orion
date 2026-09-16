package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiator;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiatorIterator;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/**
 * Owns fetch negotiation, object-access checks, and the native pack producer for one fetch operation.
 * Owns FetchNegotiator over borrowed byte streams. FetchRequest parses version-specific requests;
 * FetchNegotiatorIterator operates on decoded messages and accumulates NegotiationContext.
 * Access checks precede storage reads, negotiation decisions, and pack production.
 * Owns GitStorageApi and lends it to a fresh NegotiationContext for each negotiation.
 * Passes the advertised legacy names or decoded v2 fetch features to that context for request validation.
 * negotiate reads the request, prepares object-based negotiation, then starts the wire loop.
 * prepareNegotiation validates capabilities, calls checkFetchAccess, resolves want-ref using one storage snapshot,
 * and checks each distinct wanted object's presence before have/done. Requests without want-ref need no snapshot.
 * checkFetchAccess is a user-policy hook: the default allows fetch; an override can throw IOException to deny it.
 * The application supplies user identity through the command subclass. The hook must not mutate the request.
 * Existing objects need not be ref tips or reachable from advertised refs; no reachability restriction is imposed.
 * Object presence is not authorization or graph readiness. Missing objects and storage failures abort preparation.
 * Graph readiness in the context remains an explicit unsupported operation until traversal and stored-delta base lookup
 * are specified. It must account for every want, resolved want-ref, and shallow boundary, not merely the
 * existence of one common object. Application-specific authorization and storage implementations remain external.
 * The command releases its producer on success, failure, or cancellation.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code prepareNegotiation(FetchRequest, GitTransport)} - validate capabilities and create the iterator;
 *       check access, resolve requested refs, and verify object availability.</li>
 *   <li>{@code negotiate()} - let FetchNegotiator drive the iterator and return its accumulated context.</li>
 *   <li>{@code prepareResponse()} - prepare pack production and return response metadata.</li>
 *   <li>{@code writePack(BufferedByteOutput)} - stream the producer into writer-provided output.</li>
 *   <li>{@code close()} - release command-owned production resources.</li>
 * </ul>
 * Method names and signatures are provisional; the producer must not leak into the wire-layer contract.
 */
public class FetchCommand implements GitCommand {
    private final GitStorageApi storage;
    private final Set<GitCapability> advertisedCapabilities;

    public FetchCommand(GitStorageApi storage, Set<GitCapability> advertisedCapabilities) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.advertisedCapabilities = Set.copyOf(advertisedCapabilities);
    }

    public NegotiationContext negotiate(FetchNegotiator negotiator, GitTransport transport) throws IOException {
        FetchRequest request = negotiator.readRequest();
        FetchNegotiatorIterator iterator = prepareNegotiation(request, transport);
        return negotiator.negotiate(iterator);
    }

    public FetchNegotiatorIterator prepareNegotiation(FetchRequest request, GitTransport transport)
            throws IOException {
        var context = new NegotiationContext(request, storage, advertisedCapabilities);
        var iterator = new FetchNegotiatorIterator(context, transport);
        checkFetchAccess(request);
        if (!request.wantRefs().isEmpty()) {
            context.resolveWantedRefs(storage.snapshotRefs());
        }
        for (ObjectId objectId : context.wantedObjects()) {
            if (!storage.exists(objectId)) {
                throw new IOException("Wanted object does not exist: " + objectId.toHex());
            }
        }
        return iterator;
    }

    protected void checkFetchAccess(FetchRequest request) throws IOException {
    }

}
