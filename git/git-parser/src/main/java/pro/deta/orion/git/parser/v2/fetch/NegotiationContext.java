package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Accumulates one negotiation's result independently of bytes, packet encoding, and repository storage.
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
public final class NegotiationContext {
    private final FetchRequest request;
    private final Set<ObjectId> commonObjects = new LinkedHashSet<>();
    private ObjectId lastCommon;
    private boolean ready;
    private boolean doneReceived;

    public NegotiationContext(FetchRequest request) {
        this.request = Objects.requireNonNull(request, "request");
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

    void markReady() {
        ready = true;
    }

    void markDone() {
        doneReceived = true;
    }
}
