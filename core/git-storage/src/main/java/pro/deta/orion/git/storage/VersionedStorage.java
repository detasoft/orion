package pro.deta.orion.git.storage;

import pro.deta.orion.util.Result;

import java.util.List;
import java.util.Map;

public interface VersionedStorage {
    default Result<VersionedFileSnapshot> load(String path) {
        return load(List.of(path));
    }

    Result<VersionedFileSnapshot> load(List<String> paths);

    void save(Map<String, byte[]> files, VersionedSaveRequest request);
}
