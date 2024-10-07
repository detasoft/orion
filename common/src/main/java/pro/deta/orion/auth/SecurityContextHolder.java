package pro.deta.orion.auth;

public class SecurityContextHolder implements AutoCloseable {
    private static final ThreadLocal<SecurityContext> sc = ThreadLocal.withInitial(SecurityContext::createContext);

    public SecurityContextHolder() {
        sc.set(SecurityContext.createContext());
    }

    public static SecurityContext getSc() {
        return sc.get();
    }

    @Override
    public void close() {
        sc.remove();
    }
}
