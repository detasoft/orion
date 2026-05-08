package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionEnableServiceSupport;
import pro.deta.orion.util.Result;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class JDBCAccessControlStorage extends OrionEnableServiceSupport implements AccessControlStorage {
    private final OrionConfiguration.AccessControlConfig config;

    @Override
    public Result<AccessControlSnapshot> load() {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {

    }

    @Override
    public String primaryPath() {
        return config.getSettingsFileName();
    }
}
