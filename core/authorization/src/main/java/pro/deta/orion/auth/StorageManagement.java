package pro.deta.orion.auth;

import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.S3StorageBinding;

import java.util.List;
import java.util.Optional;

/** Authorized storage configuration and repository creation shared by HTTP and SSH. */
public interface StorageManagement {
    Outcome<Created> createRepository(SecurityContext actor, String name, Optional<S3StorageBinding> storage);
    Outcome<Connections> connections(SecurityContext actor, Optional<OrganizationId> owner);
    Outcome<Connections> saveConnection(SecurityContext actor, Optional<OrganizationId> owner,
            String revision, boolean create, S3Input input);

    record Created(boolean created) {}
    record Connections(String revision, List<S3View> connections) {}
    record S3View(String name, String endpoint, String region, boolean pathStyleAccess,
            String accessKeyId, boolean credentialsConfigured, boolean sessionTokenConfigured,
            boolean canChange, boolean canUse) {}

    /** Null credential fields preserve their previous values on update. */
    record S3Input(String name, String endpoint, String region, boolean pathStyleAccess,
            String accessKeyId, char[] secretKey, char[] sessionToken, boolean defaultCredentials) {
        @Override
        public String toString() { return "S3Input[credentials=redacted]"; }
    }

    sealed interface Outcome<T> permits Success, Failure {}
    record Success<T>(T value) implements Outcome<T> {}
    record Failure<T>(FailureCode code, String message) implements Outcome<T> {}
    enum FailureCode { DENIED, INVALID, CONFLICT, UNAVAILABLE, STORAGE_RETRY }
}
