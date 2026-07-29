package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;

import java.util.Map;
import java.util.Objects;

public class NativeGitRepository {
    private final String name;
    private final LooseRefStore looseRefStore;
    private final LooseObjectStore looseObjectStore;
    private final String defaultHead;

    public NativeGitRepository(
            String name,
            LooseRefStore looseRefStore,
            LooseObjectStore looseObjectStore,
            String defaultHead) {
        this.name = Objects.requireNonNull(name, "name");
        this.looseRefStore = Objects.requireNonNull(
                looseRefStore,
                "looseRefStore");
        this.looseObjectStore = Objects.requireNonNull(
                looseObjectStore,
                "looseObjectStore");
        this.defaultHead = Objects.requireNonNull(defaultHead, "defaultHead");
    }

    public String name() {
        return name;
    }

    public String defaultHead() {
        return defaultHead;
    }

    public Map<String, String> refs() {
        return looseRefStore.snapshot();
    }

    public RefUpdateResult updateRef(
            String refName,
            String expectedOldId,
            String newId) {
        return looseRefStore.update(
                refName,
                expectedOldId,
                newId);
    }
}
