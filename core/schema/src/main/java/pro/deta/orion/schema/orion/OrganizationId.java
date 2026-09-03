package pro.deta.orion.schema.orion;

public record OrganizationId(String value) {
    public OrganizationId {
        value = IdentifierRules.requireCanonical(value, "organization id");
        if (PrincipalAddress.SYSTEM_SCOPE.equals(value)) {
            throw new IllegalArgumentException("organization id is reserved for system principals: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
