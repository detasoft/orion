package pro.deta.orion.schema.acl;

import java.util.List;

public class ACLUtil {
    public static AccessControl generateDefaultAccessControl(String defaultRootPasswordHash) {
        return generateDefaultAccessControl(defaultRootPasswordHash, AccessControl.CredentialType.ARGON2);
    }

    public static AccessControl generateDefaultAccessControl(
            String defaultRootPasswordHash,
            AccessControl.CredentialType passwordCredentialType) {
        AccessControl.Grant connectFromLocalhost = new AccessControl.Grant("CONNECT", List.of(
                new AccessControl.GrantExpression(AccessControl.GrantKey.NETWORK_SOURCE, "127.0.0.1")));
        AccessControl.Grant allRepository = new AccessControl.Grant("ALL_REPOSITORY", List.of(
                new AccessControl.GrantExpression(AccessControl.GrantKey.REPOSITORY, "**"),
                new AccessControl.GrantExpression(AccessControl.GrantKey.READ, "true"),
                new AccessControl.GrantExpression(AccessControl.GrantKey.READ_WRITE, "true"),
                new AccessControl.GrantExpression(AccessControl.GrantKey.CREATE, "true"),
                new AccessControl.GrantExpression(AccessControl.GrantKey.BRANCH, "*"),
                new AccessControl.GrantExpression(AccessControl.GrantKey.FORCE, "true")));
        AccessControl.Grant applicationControl = new AccessControl.Grant("APPLICATION_CONTROL", List.of(
                new AccessControl.GrantExpression(AccessControl.GrantKey.SHUTDOWN, "true"),
                new AccessControl.GrantExpression(AccessControl.GrantKey.ADMIN, "true")));
        AccessControl.Role rootRole = new AccessControl.Role("ROOT", List.of(), List.of(
                connectFromLocalhost.getId(), allRepository.getId(), applicationControl.getId()));
        AccessControl.User rootUser = new AccessControl.User("root", null, null, "root@orion.pro",
                List.of(new AccessControl.Credential(passwordCredentialType, defaultRootPasswordHash)),
                List.of(rootRole.getId()), List.of());
        return new AccessControl(List.of(rootUser), List.of(rootRole),
                List.of(connectFromLocalhost, allRepository, applicationControl));
    }
}
