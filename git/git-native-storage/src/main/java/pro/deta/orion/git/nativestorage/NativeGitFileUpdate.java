package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import java.util.List;
import java.util.Objects;

public record NativeGitFileUpdate(
        LooseObjectStore objects,
        List<LooseRefStore.Update> refUpdates) {
    public NativeGitFileUpdate {
        Objects.requireNonNull(objects, "objects");
        refUpdates = List.copyOf(refUpdates);
    }
}
