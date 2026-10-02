package pro.deta.orion.schema.acl;

import java.util.List;

public record User(String id, String first, String last, String email,
        List<Credential> credentials, List<String> roles, List<Grant> grants) {
    public User {
        credentials = AccessControl.immutableElements(credentials);
        roles = roles == null ? List.of() : List.copyOf(roles);
        grants = AccessControl.immutableElements(grants);
    }
}
