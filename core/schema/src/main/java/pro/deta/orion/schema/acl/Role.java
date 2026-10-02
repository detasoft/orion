package pro.deta.orion.schema.acl;

import java.util.List;

public record Role(String id, List<Grant> grants, List<String> grantReferences) {
    public Role {
        grants = AccessControl.immutableElements(grants);
        grantReferences = grantReferences == null ? List.of() : List.copyOf(grantReferences);
    }
}
