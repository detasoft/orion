package pro.deta.orion.acl.storage;

import pro.deta.orion.git.storage.VersionedFileSnapshot;
import pro.deta.orion.git.storage.VersionedSaveRequest;
import pro.deta.orion.git.storage.VersionedStorage;
import pro.deta.orion.util.Result;

import java.util.List;
import java.util.Objects;

public class VersionedAccessControlStorage implements AccessControlStorage {
    private final VersionedStorage versionedStorage;
    private final String primaryPath;

    public VersionedAccessControlStorage(VersionedStorage versionedStorage, String primaryPath) {
        this.versionedStorage = Objects.requireNonNull(versionedStorage, "versionedStorage");
        this.primaryPath = Objects.requireNonNull(primaryPath, "primaryPath");
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        return switch (versionedStorage.load(List.of(primaryPath))) {
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
        return primaryPath;
    }
}
