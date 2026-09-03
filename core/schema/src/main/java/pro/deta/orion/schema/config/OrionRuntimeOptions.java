package pro.deta.orion.schema.config;

public record OrionRuntimeOptions(boolean resetRootPassword) {
    public static OrionRuntimeOptions defaults() {
        return new OrionRuntimeOptions(false);
    }
}
