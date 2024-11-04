package pro.deta.orion.internal.system;

public interface OrionJavaLangAccess {
    static OrionJavaLangAccess create() {
        try {
            return new JavaLangAccessInterop();
        } catch (Throwable e) {
            return new OrionJavaLangAccess() {
            };
        }
    }

    default void setCause(Throwable t, Throwable cause) {
    }
}
