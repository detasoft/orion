package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.OrionEnableServiceSupport;
import pro.deta.orion.util.Pair;
import pro.deta.orion.util.Result;

import java.util.Optional;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class JDBCAccessControlStorage extends OrionEnableServiceSupport implements AccessControlStorage {
    private final OrionConfiguration.AccessControlConfig config;

    @Override
    public Result<AccessControl> loadAccessControl() {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public void saveAccessControl(AccessControl accessControl, String message, UserEmail author) {

    }
}
