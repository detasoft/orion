package pro.deta.orion.schema.config;

public record OrionRuntimeOptions(boolean regenerateSshEnrollmentToken) {
    public static OrionRuntimeOptions defaults() {
        return new OrionRuntimeOptions(false);
    }
}
