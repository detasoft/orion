package pro.deta.orion.auth.check.resource;

import pro.deta.orion.auth.check.RootResource;

/**
 * Application-level resource for administrative operations such as managing users and repositories.
 */
public record ApplicationAdminResource() implements RootResource {
    private static final ApplicationAdminResource INSTANCE = new ApplicationAdminResource();

    public static ApplicationAdminResource applicationAdmin() {
        return INSTANCE;
    }

    @Override
    public String describe() {
        return "application admin";
    }
}
