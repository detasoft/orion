package pro.deta.orion.schema.orion;

public record RemoteAlias(String value) {
    public static final RemoteAlias UPSTREAM = new RemoteAlias("upstream");

    public RemoteAlias {
        value = IdentifierRules.requireCanonical(value, "remote alias");
    }

    @Override
    public String toString() {
        return value;
    }
}
