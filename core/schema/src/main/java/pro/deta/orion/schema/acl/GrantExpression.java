package pro.deta.orion.schema.acl;

import pro.deta.orion.schema.acl.AccessControl.GrantKey;

public record GrantExpression(GrantKey key, String value) {
}
