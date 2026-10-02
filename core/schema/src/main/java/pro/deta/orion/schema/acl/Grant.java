package pro.deta.orion.schema.acl;

import java.util.List;

public record Grant(String id, List<GrantExpression> info) {
    public Grant {
        info = AccessControl.immutableElements(info);
    }
}
