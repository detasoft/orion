package pro.deta.orion.acl.schema;

import java.util.ArrayList;

public class ACLUtil {
    public static AccessControl generateDefaultAccessControl(String defaultRootPasswordHash) {
        return generateDefaultAccessControl(defaultRootPasswordHash, AccessControl.CredentialType.ARGON2);
    }

    public static AccessControl generateDefaultAccessControl(
            String defaultRootPasswordHash,
            AccessControl.CredentialType passwordCredentialType) {
        AccessControlDraft draft = new AccessControlDraft();
        AccessControlDraft.Grant connectFromLocalhost = createGrant("CONNECT")
                .addKey(AccessControl.GrantKey.NETWORK_SOURCE, "127.0.0.1");

        AccessControlDraft.Grant allRepository = createGrant("ALL_REPOSITORY")
                .addKey(AccessControl.GrantKey.REPOSITORY, "*")
                .addKey(AccessControl.GrantKey.READ, "true")
                .addKey(AccessControl.GrantKey.WRITE, "true")
                .addKey(AccessControl.GrantKey.CREATE, "true")
                .addKey(AccessControl.GrantKey.BRANCH, "*")
                .addKey(AccessControl.GrantKey.FORCE, "true");
        AccessControlDraft.Grant applicationControl = createGrant("APPLICATION_CONTROL")
                .addKey(AccessControl.GrantKey.SHUTDOWN, "true")
                .addKey(AccessControl.GrantKey.ADMIN, "true");

        AccessControlDraft.Role rootRole = createRole("ROOT")
                .addGrantReference(connectFromLocalhost.getId())
                .addGrantReference(allRepository.getId())
                .addGrantReference(applicationControl.getId());

        AccessControlDraft.User rootUser = createUser("root", "root@orion.pro")
                .addCredential(passwordCredentialType, defaultRootPasswordHash)
                .addRole(rootRole.getId());


        draft.getUsers().add(rootUser);
        draft.getRoles().add(rootRole);
        draft.getGrants().add(connectFromLocalhost);
        draft.getGrants().add(allRepository);
        draft.getGrants().add(applicationControl);
        return draft.toAccessControl();
    }


    public static AccessControlDraft.User createUser(String id, String email) {
        return new AccessControlDraft.User(id, null, null, email, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public static AccessControlDraft.Role createRole(String id) {
        return new AccessControlDraft.Role(id, new ArrayList<>(), new ArrayList<>());
    }

    public static AccessControlDraft.Grant createGrant(String id) {
        return new AccessControlDraft.Grant(id, new ArrayList<>());
    }
}
