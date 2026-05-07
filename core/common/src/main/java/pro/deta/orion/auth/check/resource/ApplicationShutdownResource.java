package pro.deta.orion.auth.check.resource;

import pro.deta.orion.auth.check.RootResource;

/**
 * Application-level resource for the shutdown command. It has no payload because the protected target is global.
 */
public record ApplicationShutdownResource() implements RootResource {
    private static final ApplicationShutdownResource INSTANCE = new ApplicationShutdownResource();

    public static ApplicationShutdownResource applicationShutdown() {
        return INSTANCE;
    }

    @Override
    public String describe() {
        return "application shutdown";
    }
}
