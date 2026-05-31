package pro.deta.orion.transport.http;

final class OrionAdminPaths {
    static final String ADMIN = "/api/admin";
    static final String USERS = ADMIN + "/users";
    static final String REPOSITORIES = ADMIN + "/repositories";
    static final String ACCESS_CONTROL = ADMIN + "/acl";
    static final String LIFECYCLE_TRANSPORTS = ADMIN + "/lifecycle/transports";
    static final String ROUTES = ADMIN + "/routes";
    static final String SHUTDOWN = ADMIN + "/shutdown";
    static final String ACME_CERTIFICATE = ADMIN + "/acme/certificate";
    static final String TOKEN = ADMIN + "/token";

    private OrionAdminPaths() {
    }
}
