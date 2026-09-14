package pro.deta.orion.schema.orion;

public record ConfigurationSecret(String id, String envelope) {
    public ConfigurationSecret {
        id = IdentifierRules.requireCanonical(id, "secret id");
        if (envelope == null || envelope.isBlank()) {
            throw new IllegalArgumentException("secret envelope must not be empty");
        }
    }

    @Override
    public String toString() {
        return "ConfigurationSecret[id=" + id + ", envelope=<redacted>]";
    }
}
