package pro.deta.orion.acl.schema;

import java.util.ArrayList;

public class ACLUtil {
    public static AccessControl generateDefaultAccessControl(String defaultRootPasswordHash) {
        return generateDefaultAccessControl(defaultRootPasswordHash, AccessControl.CredentialType.ARGON2);
    }

    public static AccessControl generateDefaultAccessControl(
            String defaultRootPasswordHash,
            AccessControl.CredentialType passwordCredentialType) {
        AccessControl s = new AccessControl();
        AccessControl.Grant connectFromLocalhost = createGrant("CONNECT")
                .addKey(AccessControl.GrantKey.NETWORK_SOURCE, "127.0.0.1");

        AccessControl.Grant allRepository = createGrant("ALL_REPOSITORY")
                .addKey(AccessControl.GrantKey.REPOSITORY, "*")
                .addKey(AccessControl.GrantKey.READ, "true")
                .addKey(AccessControl.GrantKey.WRITE, "true")
                .addKey(AccessControl.GrantKey.CREATE, "true")
                .addKey(AccessControl.GrantKey.BRANCH, "*")
                .addKey(AccessControl.GrantKey.FORCE, "true");
        AccessControl.Grant applicationControl = createGrant("APPLICATION_CONTROL")
                .addKey(AccessControl.GrantKey.SHUTDOWN, "true")
                .addKey(AccessControl.GrantKey.ADMIN, "true");

        AccessControl.Role rootRole = createRole("ROOT")
                .addGrantReference(connectFromLocalhost.getId())
                .addGrantReference(allRepository.getId())
                .addGrantReference(applicationControl.getId());

        AccessControl.User rootUser = createUser("root", "root@orion.pro")
                .addCredential(passwordCredentialType, defaultRootPasswordHash)
                .addCredential(AccessControl.CredentialType.STATIC_BEARER_TOKEN, defaultRootPasswordHash)
                .addRole(rootRole.getId());


        s.getUsers().add(rootUser);
        s.getRoles().add(rootRole);
        s.getGrants().add(connectFromLocalhost);
        s.getGrants().add(allRepository);
        s.getGrants().add(applicationControl);
        return s;
    }


    public static AccessControl.User createUser(String id, String email) {
        return new AccessControl.User(id, null, null, email, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public static AccessControl.Role createRole(String id) {
        return new AccessControl.Role(id, new ArrayList<>(), new ArrayList<>());
    }

    public static AccessControl.Grant createGrant(String id) {
        return new AccessControl.Grant(id, new ArrayList<>());
    }
}
