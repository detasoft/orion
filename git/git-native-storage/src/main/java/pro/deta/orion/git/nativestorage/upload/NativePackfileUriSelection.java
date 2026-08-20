package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record NativePackfileUriSelection(
        List<NativePackfileUri> packfileUris,
        Set<GitObjectId> objectIds) {
    public static NativePackfileUriSelection empty() {
        return new NativePackfileUriSelection(List.of(), Set.of());
    }

    public NativePackfileUriSelection {
        Objects.requireNonNull(packfileUris, "packfileUris");
        Objects.requireNonNull(objectIds, "objectIds");
        packfileUris = List.copyOf(packfileUris);
        objectIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(objectIds));
    }
}
