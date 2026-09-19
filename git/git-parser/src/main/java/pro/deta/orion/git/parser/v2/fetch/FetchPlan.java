package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

public record FetchPlan(
        Set<ObjectId> wantedObjects,
        Map<RefId, ObjectId> wantedRefs,
        Set<ObjectId> commonObjects,
        Set<ObjectId> shallowCommits,
        OptionalInt depth,
        OptionalLong deepenSince,
        Set<String> deepenNot,
        Optional<String> filter,
        GitCapabilities capabilities,
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
        capabilities = (GitCapabilities) capabilities.clone();
        packfileUriProtocols = snapshot(packfileUriProtocols);
    }

    @Override
    public GitCapabilities capabilities() {
        return (GitCapabilities) capabilities.clone();
    }

    private static <T> Set<T> snapshot(Set<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
