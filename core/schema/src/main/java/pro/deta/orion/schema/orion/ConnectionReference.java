package pro.deta.orion.schema.orion;

/** An explicit connection scope; organization references resolve only in the enclosing organization. */
public record ConnectionReference(Scope scope, String name) {
    public ConnectionReference {
        java.util.Objects.requireNonNull(scope, "connection scope");
        name = IdentifierRules.requireCanonical(name, "connection name");
    }

    public enum Scope { SYSTEM, ORGANIZATION }
}
