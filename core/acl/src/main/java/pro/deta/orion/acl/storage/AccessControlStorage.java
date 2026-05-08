package pro.deta.orion.acl.storage;

import pro.deta.orion.util.Result;

public interface AccessControlStorage {
    Result<AccessControlSnapshot> load();

    void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request);

    String primaryPath();
}
