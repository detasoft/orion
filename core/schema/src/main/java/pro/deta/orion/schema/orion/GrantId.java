package pro.deta.orion.schema.orion;

public record GrantId(String value) {
    public GrantId {
        value = IdentifierRules.requireCanonical(value, "grant id");
    }

    @Override
    public String toString() {
        return value;
    }
}
