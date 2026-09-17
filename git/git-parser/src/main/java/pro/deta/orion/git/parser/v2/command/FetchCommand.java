package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiator;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiatorIterator;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.data.FetchPlan;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Coordinates fetch negotiation, object-access checks, and preparation of pack-production inputs.
 * FetchNegotiator uses borrowed byte streams. FetchRequest parses version-specific requests;
 * FetchNegotiatorIterator operates on decoded messages and accumulates NegotiationContext.
 * Access checks precede storage reads, negotiation decisions, and pack production.
 * Owns GitStorageApi and lends it to a fresh NegotiationContext for each negotiation.
 * Passes the advertised legacy names or decoded v2 fetch features to that context for request validation.
 * negotiate reads the request, prepares object-based negotiation, then drives the wire loop and returns
 * Optional<FetchPlan>. An empty result means the exchange ended without permitting pack transmission.
 * prepareNegotiation validates capabilities, calls checkFetchAccess, resolves want-ref using one storage snapshot,
 * and checks each distinct wanted object's presence before have/done. Requests without want-ref need no snapshot.
 * checkFetchAccess is a user-policy hook: the default allows fetch; an override can throw IOException to deny it.
 * The application supplies user identity through the command subclass. The hook must not mutate the request.
 * Existing objects need not be ref tips or reachable from advertised refs; no reachability restriction is imposed.
 * Object presence is not authorization or graph readiness. Missing objects and storage failures abort preparation.
 * prepareResponse converts a completed exchange's context into an immutable plan without additional storage reads.
 * DONE permits transmission even without common objects. READY suffices for v2 or negotiated legacy no-done;
 * legacy READY alone still requires DONE. Empty wants do not produce a plan. The context must come from
 * completed negotiation after preparation and validation, with an unchanged request.
 * The plan snapshots requested refs, common objects, shallow/deepen/filter constraints, and negotiated options.
 * Common objects remain explicit seeds for pack selection, not a closure of assumed client-owned ancestors.
 * Pack generation and delivery are subsequent steps; a returned plan neither creates nor owns a producer.
 * Application-specific authorization and storage implementations remain external.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code prepareNegotiation(FetchRequest, GitTransport)} - validate capabilities and create the iterator;
 *       check access, resolve requested refs, and verify object availability.</li>
 *   <li>{@code negotiate()} - drive negotiation through response delivery and return an optional pack plan.</li>
 *   <li>{@code prepareResponse(context)} - decide whether the completed exchange permits pack preparation.</li>
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

    public Optional<FetchPlan> negotiate(FetchNegotiator negotiator, GitTransport transport) throws IOException {
        FetchRequest request = negotiator.readRequest();
        FetchNegotiatorIterator iterator = prepareNegotiation(request, transport);
        return prepareResponse(negotiator.negotiate(iterator));
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

    public Optional<FetchPlan> prepareResponse(NegotiationContext context) {
        Objects.requireNonNull(context, "context");
        FetchRequest request = context.request();
        boolean readyPermitsPack = context.ready() && (request.mode() == FetchRequest.Mode.PROTOCOL_V2
                || request.capabilities().contains(GitCapability.NO_DONE.entry()));
        if (!context.doneReceived() && !readyPermitsPack) {
            return Optional.empty();
        }
        Set<ObjectId> wants = context.wantedObjects();
        if (wants.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FetchPlan(wants, context.wantedRefs(), context.commonObjects(),
                request.shallowCommits(), request.depth(), request.deepenSince(), request.deepenNot(),
                request.filter(), request.capabilities(), request.packfileUriProtocols()));
    }

}
