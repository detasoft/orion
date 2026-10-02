package pro.deta.orion.schema.orion.v2;

public record RoleId(String value) {
    public RoleId {
        value = IdentifierRules.requireCanonical(value, "role id");
    }

    @Override
    public String toString() {
        return value;
    }
}
