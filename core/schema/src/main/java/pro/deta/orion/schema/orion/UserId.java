package pro.deta.orion.schema.orion;

public record UserId(String value) {
    public UserId {
        value = IdentifierRules.requireCanonical(value, "user id");
    }

    @Override
    public String toString() {
        return value;
    }
}
