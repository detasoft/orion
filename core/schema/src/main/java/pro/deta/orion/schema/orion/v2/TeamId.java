package pro.deta.orion.schema.orion.v2;

public record TeamId(String value) {
    public TeamId {
        value = IdentifierRules.requireCanonical(value, "team id");
    }

    @Override
    public String toString() {
        return value;
    }
}
