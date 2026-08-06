package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record NativeFetchResponse(
        NativePackProducer packProducer,
        Set<GitObjectId> shallowBoundaries) {

    public NativeFetchResponse {
        Objects.requireNonNull(packProducer, "packProducer");
        Objects.requireNonNull(shallowBoundaries, "shallowBoundaries");
        shallowBoundaries = Collections.unmodifiableSet(
                new LinkedHashSet<>(shallowBoundaries));
    }
}
