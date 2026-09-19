package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.*;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.read.GitObjectLinks;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Accumulates one negotiation's result independently of bytes and packet encoding.
 * Borrows GitStorageApi from the command; objectExists checks published-object presence through storage.exists.
 * isReady requires each wanted commit to reach an explicitly confirmed common object. It peels tags and
 * traverses commit parents, stopping at client shallow boundaries. Trees and blobs need no history negotiation.
 * This conservative check does not infer additional common ancestors from a have and can require extra rounds.
 * Each call uses a local iterative traversal; no object contents or graph cache survive it. Only bounded
 * header prefixes and parent IDs are retained while reading restored objects through ResolvedGitObjectRead.
 * Presence alone does not establish readiness or authorize wants. Depth, time, exclusion, and filter arguments
 * control subsequent pack selection; this check does not calculate the outgoing shallow boundary.
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
 * During preparation, resolveWantedRefs resolves all requested names against one supplied snapshot.
 * HEAD uses that snapshot's symbolic or detached target. Missing refs, including unborn HEAD, fail the request.
 * wantedRefs preserves requested names and order for the wanted-refs response; resolution publishes no partial map.
 * wantedObjects derives the deduplicated union of explicit wants and resolved targets without changing the request.
 * Resolution must finish before negotiation starts. It establishes neither object availability nor access rights.
 */
public class NegotiationContext {
    private final FetchRequest request;
    private final GitStorageApi storage;
    private final GitCapabilities advertisedCapabilities = new GitCapabilities();
    private final Set<ObjectId> commonObjects = new LinkedHashSet<>();
    private Map<RefId, ObjectId> wantedRefs = Map.of();
    private ObjectId lastCommon;
    private boolean ready;
    private boolean doneReceived;

    public NegotiationContext(FetchRequest request, GitStorageApi storage, GitCapabilities advertisedCapabilities) {
        this.request = Objects.requireNonNull(request, "request");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.advertisedCapabilities.addAll(Objects.requireNonNull(advertisedCapabilities, "advertisedCapabilities"));
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
        for (GitCapabilityValue entry : request.capabilities()) {
            GitCapability capability = entry.capability()
                    .orElseThrow(() -> new IOException("Unsupported fetch capability: " + entry.name()));
            validateCapabilityValue(capability, entry);
            if (v2) {
                validateV2Capability(capability);
            } else {
                validateLegacyCapability(capability);
            }
        }
    }

    private void validateCapabilityValue(GitCapability capability, GitCapabilityValue entry) throws IOException {
        boolean valued = switch (capability) {
            case AGENT, SESSION_ID, OBJECT_FORMAT -> true;
            default -> false;
        };
        if (entry.value().isPresent() != valued) {
            throw new IOException("Invalid fetch capability value: " + entry.wireToken());
        }
        if (capability == GitCapability.OBJECT_FORMAT) {
            GitCapabilityValue.parse(entry.wireToken(), GitHashAlgorithm.SHA1);
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
        if (!advertisedCapabilities.has(capability)) {
            throw new IOException("Fetch capability was not advertised: " + capability.wireName());
        }
    }

    public boolean objectExists(ObjectId objectId) throws IOException {
        return storage.exists(objectId);
    }

    public void resolveWantedRefs(RefsSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        var resolved = new LinkedHashMap<RefId, ObjectId>();
        for (String name : request.wantRefs()) {
            var ref = new RefId(name);
            ObjectId target;
            if (name.equals("HEAD")) {
                target = switch (snapshot.head()) {
                    case Head.Symbolic head -> snapshot.refs().get(head.target());
                    case Head.Detached head -> new ObjectId(head.target().toBytes());
                };
            } else {
                target = snapshot.refs().get(ref);
            }
            if (target == null) {
                throw new IOException("Unknown wanted ref: " + name);
            }
            resolved.put(ref, target);
        }
        wantedRefs = Collections.unmodifiableMap(resolved);
    }

    public Map<RefId, ObjectId> wantedRefs() {
        return wantedRefs;
    }

    public Set<ObjectId> wantedObjects() {
        for (String name : request.wantRefs()) {
            if (!wantedRefs.containsKey(new RefId(name))) {
                throw new IllegalStateException("Wanted refs must be resolved before reading wanted objects");
            }
        }
        var objects = new LinkedHashSet<>(request.wants());
        objects.addAll(wantedRefs.values());
        return Collections.unmodifiableSet(objects);
    }

    public boolean isReady() throws IOException {
        Set<ObjectId> wants = wantedObjects();
        if (wants.isEmpty() || commonObjects.isEmpty()) {
            return false;
        }
        ResolvedGitObjectRead<GitObjectLinks> reader = new ResolvedGitObjectRead<>(storage,
                (type, size, base, input) -> type == GitObjectType.TREE || type == GitObjectType.BLOB
                        ? new GitObjectLinks(type, List.of()) : GitObjectLinks.read(type, size, base, input));
        for (ObjectId want : wants) {
            if (!reachesCommon(want, reader)) {
                return false;
            }
        }
        return true;
    }

    private boolean reachesCommon(ObjectId want, ResolvedGitObjectRead<GitObjectLinks> reader) throws IOException {
        ArrayDeque<GraphVisit> pending = new ArrayDeque<>();
        Set<ObjectId> visited = new HashSet<>();
        pending.add(new GraphVisit(want, false));
        while (!pending.isEmpty()) {
            GraphVisit visit = pending.removeFirst();
            ObjectId id = visit.id();
            if (commonObjects.contains(id)) {
                return true;
            }
            if (!visited.add(id)) {
                continue;
            }
            GitObjectLinks links = storage.readObject(id, reader)
                    .orElseThrow(() -> new IOException("Missing fetch history object: " + id.toHex()));
            if (visit.commitOnly() && links.type() != GitObjectType.COMMIT) {
                throw new IOException("Commit parent is not a commit: " + id.toHex());
            }
            switch (links.type()) {
                case TREE, BLOB -> {
                    return true;
                }
                case COMMIT -> {
                    if (!request.shallowCommits().contains(id)) {
                        for (int i = 1; i < links.targets().size(); i++) {
                            pending.addLast(new GraphVisit(links.targets().get(i), true));
                        }
                    }
                }
                case TAG -> pending.addLast(new GraphVisit(links.targets().getFirst(), false));
                case OFS_DELTA, REF_DELTA -> throw new IOException("Fetch history object was not resolved");
            }
        }
        return false;
    }

    private record GraphVisit(ObjectId id, boolean commitOnly) {}

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
        return request().capabilities().contains(GitCapabilityValue.value(capability));
    }

    void markReady() {
        ready = true;
    }

    void markDone() {
        doneReceived = true;
    }
}
