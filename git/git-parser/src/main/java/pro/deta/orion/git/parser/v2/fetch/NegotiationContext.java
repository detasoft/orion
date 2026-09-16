package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Accumulates one negotiation's result independently of bytes and packet encoding.
 * Borrows GitStorageApi from the command; objectExists checks published-object presence through storage.exists.
 * isReady will evaluate this context's wants, common objects, and shallow boundaries; graph traversal remains
 * an explicit unsupported operation until implemented. Presence alone does not establish readiness or authorize wants.
 * Checks must not mutate the context; repository failures propagate as IOException rather than negative results.
 * The iterator owns mutations; callers inspect the parsed request, confirmed common IDs, and progress.
 * Client have claims are not automatically common: addCommon is called only after successful repository
 * lookup. Duplicate IDs occupy one entry, while lastCommon follows the last confirmed claim for final ACK.
 * request retains shallow boundaries so consumers cannot infer unavailable parents from a common commit.
 *
 * <p>ready records the algorithm's readiness decision; doneReceived records the client's explicit DONE.
 * Neither fact substitutes for the other or proves that pack production succeeded. A context can be returned
 * after a stateless response with both false. Each new request has its own context; stateful rounds share one.
 * Collection getters return immutable snapshots in first-confirmation order.
 * No payloads, unknown-have set, or second graph is retained.
 * Mutation methods are package-private for the iterator; this object is not safe for concurrent mutation.
 */
public class NegotiationContext {
    private final FetchRequest request;
    private final GitStorageApi storage;
    private final Set<ObjectId> commonObjects = new LinkedHashSet<>();
    private ObjectId lastCommon;
    private boolean ready;
    private boolean doneReceived;

    public NegotiationContext(FetchRequest request, GitStorageApi storage) {
        this.request = Objects.requireNonNull(request, "request");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public boolean objectExists(ObjectId objectId) throws IOException {
        return storage.exists(objectId);
    }

    public boolean isReady() throws IOException {
        throw new UnsupportedOperationException("Fetch graph readiness is not implemented");
    }

    public FetchRequest request() {
        return request;
    }

    public Set<ObjectId> commonObjects() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(commonObjects));
    }

    public Optional<ObjectId> lastCommon() {
        return Optional.ofNullable(lastCommon);
    }

    public boolean ready() {
        return ready;
    }

    public boolean doneReceived() {
        return doneReceived;
    }

    boolean hasCommon(ObjectId objectId) {
        return commonObjects.contains(objectId);
    }

    void addCommon(ObjectId objectId) {
        commonObjects.add(Objects.requireNonNull(objectId, "objectId"));
        lastCommon = objectId;
    }

    boolean hasRequest(GitCapability capability) {
        return request().capabilities().contains(capability.entry());
    }

    void markReady() {
        ready = true;
    }

    void markDone() {
        doneReceived = true;
    }
}
