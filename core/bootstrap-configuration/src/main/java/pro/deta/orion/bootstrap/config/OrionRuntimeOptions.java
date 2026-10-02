package pro.deta.orion.bootstrap.config;

public record OrionRuntimeOptions(boolean resetRootPassword) {
    public static OrionRuntimeOptions defaults() {
        return new OrionRuntimeOptions(false);
    }
}
