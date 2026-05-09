package pro.deta.orion.acl.storage;

import pro.deta.orion.git.storage.VersionedFileSnapshot;
import pro.deta.orion.git.storage.VersionedSaveRequest;
import pro.deta.orion.git.storage.VersionedStorage;
import pro.deta.orion.util.Result;

import java.util.List;
import java.util.Objects;

public class VersionedAccessControlStorage implements AccessControlStorage {
    private final VersionedStorage versionedStorage;
    private final List<String> paths;

    public VersionedAccessControlStorage(VersionedStorage versionedStorage, List<String> paths) {
        this.versionedStorage = Objects.requireNonNull(versionedStorage, "versionedStorage");
        this.paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        if (this.paths.isEmpty()) {
            throw new IllegalArgumentException("At least one ACL path must be configured");
        }
    }

    public VersionedAccessControlStorage(VersionedStorage versionedStorage, String primaryPath) {
        this(versionedStorage, List.of(Objects.requireNonNull(primaryPath, "primaryPath")));
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        return switch (versionedStorage.load(paths)) {
            case Result.Success<VersionedFileSnapshot>(var snapshot) -> new Result.Success<>(
                    new AccessControlSnapshot(snapshot.files(), snapshot.version()));
            case Result.Failure<VersionedFileSnapshot> failure -> new Result.Failure<>(failure);
        };
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        versionedStorage.save(snapshot.files(), new VersionedSaveRequest(request.message(), request.author()));
    }

    @Override
    public String primaryPath() {
        return paths.getFirst();
    }
}
