package pro.deta.orion.schema.orion;

public record RoleId(String value) {
    public RoleId {
        value = IdentifierRules.requireCanonical(value, "role id");
    }

    @Override
    public String toString() {
        return value;
    }
}
