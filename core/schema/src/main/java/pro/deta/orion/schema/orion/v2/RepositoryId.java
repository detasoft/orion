package pro.deta.orion.schema.orion.v2;

public record RepositoryId(String value) {
    public RepositoryId {
        value = IdentifierRules.requireCanonical(value, "repository id");
    }

    @Override
    public String toString() {
        return value;
    }
}
