package pro.deta.orion.auth;

import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.CredentialType;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.Role;
import pro.deta.orion.schema.acl.User;

import java.util.List;

public final class DefaultAccessControl {
    private DefaultAccessControl() {
    }

    public static AccessControl create(String defaultRootPasswordHash) {
        return create(defaultRootPasswordHash, CredentialType.ARGON2);
    }

    public static AccessControl create(
            String defaultRootPasswordHash,
            CredentialType passwordCredentialType) {
        Grant connectFromLocalhost = new Grant("CONNECT", List.of(
                new GrantExpression(GrantKey.NETWORK_SOURCE, "127.0.0.1")));
        Grant allRepository = new Grant("ALL_REPOSITORY", List.of(
                new GrantExpression(GrantKey.REPOSITORY, "**"),
                new GrantExpression(GrantKey.READ, "true"),
                new GrantExpression(GrantKey.READ_WRITE, "true"),
                new GrantExpression(GrantKey.CREATE, "true"),
                new GrantExpression(GrantKey.BRANCH, "*"),
                new GrantExpression(GrantKey.FORCE, "true")));
        Grant applicationControl = new Grant("APPLICATION_CONTROL", List.of(
                new GrantExpression(GrantKey.SHUTDOWN, "true"),
                new GrantExpression(GrantKey.ADMIN, "true")));
        Role rootRole = new Role("ROOT", List.of(), List.of(
                connectFromLocalhost.id(), allRepository.id(), applicationControl.id()));
        User rootUser = new User("root", null, null, "root@orion.pro",
                List.of(new Credential(passwordCredentialType, defaultRootPasswordHash)),
                List.of(rootRole.id()), List.of());
        return new AccessControl(List.of(rootUser), List.of(rootRole),
                List.of(connectFromLocalhost, allRepository, applicationControl));
    }
}
