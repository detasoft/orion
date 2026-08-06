package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record NativeFetchResponse(
        NativePackProducer packProducer,
        Set<GitObjectId> shallowBoundaries,
        Map<String, GitObjectId> wantedRefs) {

    public NativeFetchResponse(
            NativePackProducer packProducer,
            Set<GitObjectId> shallowBoundaries) {
        this(packProducer, shallowBoundaries, Map.of());
    }

    public NativeFetchResponse {
        Objects.requireNonNull(packProducer, "packProducer");
        Objects.requireNonNull(shallowBoundaries, "shallowBoundaries");
        Objects.requireNonNull(wantedRefs, "wantedRefs");
        shallowBoundaries = Collections.unmodifiableSet(
                new LinkedHashSet<>(shallowBoundaries));
        wantedRefs = Collections.unmodifiableMap(
                new LinkedHashMap<>(wantedRefs));
    }
}
