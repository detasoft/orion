package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Inputs for pack preparation after negotiation permits transmission.
 * Contains wanted objects and named ref targets, explicitly confirmed common objects, client shallow
 * boundaries, history/filter constraints, and negotiated pack options. Common objects are seeds for later
 * exclusion traversal, not an expanded set of ancestors; shallow boundaries still apply to that traversal.
 * This value owns snapshots and retains no mutable request, context, storage, streams, or producer resources.
 * A plan permits subsequent preparation but does not claim that a pack has been generated or delivered.
 * Wire framing and acknowledgment state remain with the command and negotiator.
 */
public record FetchPlan(
        Set<ObjectId> wantedObjects,
        Map<RefId, ObjectId> wantedRefs,
        Set<ObjectId> commonObjects,
        Set<ObjectId> shallowCommits,
        OptionalInt depth,
        OptionalLong deepenSince,
        Set<String> deepenNot,
        Optional<String> filter,
        Set<GitCapability.Entry> capabilities,
        Set<String> packfileUriProtocols) {
    public FetchPlan {
        wantedObjects = snapshot(wantedObjects);
        wantedRefs = Collections.unmodifiableMap(new LinkedHashMap<>(wantedRefs));
        commonObjects = snapshot(commonObjects);
        shallowCommits = snapshot(shallowCommits);
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(deepenSince, "deepenSince");
        deepenNot = snapshot(deepenNot);
        Objects.requireNonNull(filter, "filter");
        capabilities = snapshot(capabilities);
        packfileUriProtocols = snapshot(packfileUriProtocols);
    }

    private static <T> Set<T> snapshot(Set<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
