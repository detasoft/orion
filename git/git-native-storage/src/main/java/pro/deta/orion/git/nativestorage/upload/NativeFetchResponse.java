package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record NativeFetchResponse(
        NativePackProducer packProducer,
        Set<GitObjectId> shallowBoundaries,
        Set<GitObjectId> unshallowBoundaries,
        Map<String, GitObjectId> wantedRefs,
        List<NativePackfileUri> packfileUris) {

    public NativeFetchResponse(
            NativePackProducer packProducer,
            Set<GitObjectId> shallowBoundaries) {
        this(packProducer, shallowBoundaries, Set.of(), Map.of(), List.of());
    }

    public NativeFetchResponse(
            NativePackProducer packProducer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs) {
        this(packProducer, shallowBoundaries, Set.of(), wantedRefs, List.of());
    }

    public NativeFetchResponse(
            NativePackProducer packProducer,
            Set<GitObjectId> shallowBoundaries,
            Map<String, GitObjectId> wantedRefs,
            List<NativePackfileUri> packfileUris) {
        this(
                packProducer,
                shallowBoundaries,
                Set.of(),
                wantedRefs,
                packfileUris);
    }

    public NativeFetchResponse {
        Objects.requireNonNull(packProducer, "packProducer");
        Objects.requireNonNull(shallowBoundaries, "shallowBoundaries");
        Objects.requireNonNull(unshallowBoundaries, "unshallowBoundaries");
        Objects.requireNonNull(wantedRefs, "wantedRefs");
        Objects.requireNonNull(packfileUris, "packfileUris");
        shallowBoundaries = Collections.unmodifiableSet(
                new LinkedHashSet<>(shallowBoundaries));
        unshallowBoundaries = Collections.unmodifiableSet(
                new LinkedHashSet<>(unshallowBoundaries));
        wantedRefs = Collections.unmodifiableMap(
                new LinkedHashMap<>(wantedRefs));
        packfileUris = List.copyOf(packfileUris);
    }
}
