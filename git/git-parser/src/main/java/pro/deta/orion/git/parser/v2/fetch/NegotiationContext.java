package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.GitTransport;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.capability.GitObjectFormat;

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
 * Advertised capabilities are a snapshot supplied by the command: legacy names or decoded v2 fetch features.
 * V2 base arguments need no individual advertisement. The caller must advertise only implemented features;
 * this check grants no object access and does not implement filtering, shallow traversal, or pack production.
 * The dispatcher must validate v2 command-header capabilities before fetch arguments reach this context.
 */
public class NegotiationContext {
    private final FetchRequest request;
    private final GitStorageApi storage;
    private final Set<GitCapability> advertisedCapabilities;
    private final Set<ObjectId> commonObjects = new LinkedHashSet<>();
    private ObjectId lastCommon;
    private boolean ready;
    private boolean doneReceived;

    public NegotiationContext(FetchRequest request, GitStorageApi storage, Set<GitCapability> advertisedCapabilities) {
        this.request = Objects.requireNonNull(request, "request");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.advertisedCapabilities = Set.copyOf(advertisedCapabilities);
    }

    void validateCapabilities(GitTransport transport) throws IOException {
        boolean v2 = request.mode() == FetchRequest.Mode.PROTOCOL_V2;
        validateRequestedCapabilities(v2);
        validateSharedArguments();
        if (v2) {
            validateV2Arguments();
        } else {
            validateLegacyArguments(transport);
        }
    }

    private void validateRequestedCapabilities(boolean v2) throws IOException {
        for (GitCapability.Entry entry : request.capabilities()) {
            GitCapability capability = GitCapability.fromWireName(entry.name())
                    .orElseThrow(() -> new IOException("Unsupported fetch capability: " + entry.name()));
            validateCapabilityValue(capability, entry);
            if (v2) {
                validateV2Capability(capability);
            } else {
                validateLegacyCapability(capability);
            }
        }
    }

    private void validateCapabilityValue(GitCapability capability, GitCapability.Entry entry) throws IOException {
        boolean valued = switch (capability) {
            case AGENT, SESSION_ID, OBJECT_FORMAT -> true;
            default -> false;
        };
        if (entry.value().isPresent() != valued) {
            throw new IOException("Invalid fetch capability value: " + entry.wireToken());
        }
        if (capability == GitCapability.OBJECT_FORMAT) {
            GitCapability.parse(entry.wireToken(), GitObjectFormat.SHA1);
        }
    }

    private void validateV2Capability(GitCapability capability) throws IOException {
        switch (capability) {
            case THIN_PACK, OFS_DELTA, INCLUDE_TAG, NO_PROGRESS -> { }
            case DEEPEN_RELATIVE -> requireAdvertised(GitCapability.SHALLOW);
            case SIDEBAND_ALL, WAIT_FOR_DONE -> requireAdvertised(capability);
            default -> throw new IOException("Unsupported v2 fetch capability: " + capability.wireName());
        }
    }

    private void validateLegacyCapability(GitCapability capability) throws IOException {
        switch (capability) {
            case MULTI_ACK, MULTI_ACK_DETAILED, NO_DONE, THIN_PACK, OFS_DELTA,
                 SIDE_BAND, SIDE_BAND_64K, INCLUDE_TAG, NO_PROGRESS, SHALLOW,
                 DEEPEN_SINCE, DEEPEN_NOT, DEEPEN_RELATIVE, FILTER,
                 ALLOW_TIP_SHA1_IN_WANT, ALLOW_REACHABLE_SHA1_IN_WANT,
                 AGENT, SESSION_ID, OBJECT_FORMAT -> requireAdvertised(capability);
            default -> throw new IOException("Unsupported legacy fetch capability: " + capability.wireName());
        }
    }

    private void validateSharedArguments() throws IOException {
        if (!request.shallowCommits().isEmpty() || request.depth().isPresent()
                || request.deepenSince().isPresent() || !request.deepenNot().isEmpty()) {
            requireAdvertised(GitCapability.SHALLOW);
        }
        if (request.filter().isPresent()) {
            requireAdvertised(GitCapability.FILTER);
        }
    }

    private void validateV2Arguments() throws IOException {
        if (!request.wantRefs().isEmpty()) {
            requireAdvertised(GitCapability.REF_IN_WANT);
        }
        if (!request.packfileUriProtocols().isEmpty()) {
            requireAdvertised(GitCapability.PACKFILE_URIS);
        }
    }

    private void validateLegacyArguments(GitTransport transport) throws IOException {
        if (!request.wantRefs().isEmpty() || !request.packfileUriProtocols().isEmpty()) {
            throw new IOException("want-ref and packfile-uris require protocol v2");
        }
        if (request.deepenSince().isPresent()) {
            requireAdvertised(GitCapability.DEEPEN_SINCE);
        }
        if (!request.deepenNot().isEmpty()) {
            requireAdvertised(GitCapability.DEEPEN_NOT);
        }
        switch (request.mode()) {
            case MULTI_ACK -> requireAdvertised(GitCapability.MULTI_ACK);
            case MULTI_ACK_DETAILED -> requireAdvertised(GitCapability.MULTI_ACK_DETAILED);
            default -> { }
        }
        if (hasRequest(GitCapability.SIDE_BAND) && hasRequest(GitCapability.SIDE_BAND_64K)) {
            throw new IOException("side-band and side-band-64k are mutually exclusive");
        }
        if (hasRequest(GitCapability.NO_DONE)
                && (transport != GitTransport.HTTP || request.mode() != FetchRequest.Mode.MULTI_ACK_DETAILED)) {
            throw new IOException("no-done requires HTTP multi_ack_detailed negotiation");
        }
    }

    private void requireAdvertised(GitCapability capability) throws IOException {
        if (!advertisedCapabilities.contains(capability)) {
            throw new IOException("Fetch capability was not advertised: " + capability.wireName());
        }
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
