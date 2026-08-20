package pro.deta.orion.acl.schema;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Getter
@EqualsAndHashCode
@ToString
public final class AccessControl {
    public static final String TRUE_STRING = "true";

    private final List<User> users;
    private final List<Role> roles;
    private final List<Grant> grants;

    public AccessControl() {
        this(List.of(), List.of(), List.of());
    }

    public AccessControl(List<User> users, List<Role> roles, List<Grant> grants) {
        this.users = copyUsers(users);
        this.roles = copyRoles(roles);
        this.grants = copyGrants(grants);
    }

    public AccessControlDraft toDraft() {
        return AccessControlDraft.from(this);
    }

    private static List<User> copyUsers(List<User> source) {
        List<User> result = new ArrayList<>();
        for (User user : listOrEmpty(source)) {
            if (user != null) {
                result.add(new User(
                        user.getId(),
                        user.getFirst(),
                        user.getLast(),
                        user.getEmail(),
                        user.getCredentials(),
                        user.getRoles(),
                        user.getGrants()));
            }
        }
        return List.copyOf(result);
    }

    private static List<Role> copyRoles(List<Role> source) {
        List<Role> result = new ArrayList<>();
        for (Role role : listOrEmpty(source)) {
            if (role != null) {
                result.add(new Role(role.getId(), role.getGrants(), role.getGrantReferences()));
            }
        }
        return List.copyOf(result);
    }

    private static List<Grant> copyGrants(List<Grant> source) {
        List<Grant> result = new ArrayList<>();
        for (Grant grant : listOrEmpty(source)) {
            if (grant != null) {
                result.add(new Grant(grant.getId(), grant.getInfo()));
            }
        }
        return List.copyOf(result);
    }

    private static List<Credential> copyCredentials(List<Credential> source) {
        List<Credential> result = new ArrayList<>();
        for (Credential credential : listOrEmpty(source)) {
            if (credential != null) {
                result.add(new Credential(credential.getType(), credential.getKeyId(), credential.getValue()));
            }
        }
        return List.copyOf(result);
    }

    private static List<GrantExpression> copyGrantExpressions(List<GrantExpression> source) {
        List<GrantExpression> result = new ArrayList<>();
        for (GrantExpression expression : listOrEmpty(source)) {
            if (expression != null) {
                result.add(new GrantExpression(expression.getKey(), expression.getValue()));
            }
        }
        return List.copyOf(result);
    }

    private static <T> List<T> listOrEmpty(List<T> source) {
        if (source == null) {
            return List.of();
        }
        return source;
    }

    @Getter
    @EqualsAndHashCode
    @ToString
    public static final class User {
        private final String id;
        private final String first;
        private final String last;
        private final String email;
        private final List<Credential> credentials;
        private final List<String> roles;
        private final List<Grant> grants;

        public User(String id, String first, String last, String email,
                    List<Credential> credentials, List<String> roles, List<Grant> grants) {
            this.id = id;
            this.first = first;
            this.last = last;
            this.email = email;
            this.credentials = copyCredentials(credentials);
            this.roles = List.copyOf(listOrEmpty(roles));
            this.grants = copyGrants(grants);
        }
    }

    @Getter
    @EqualsAndHashCode
    @ToString
    public static final class Credential {
        private final CredentialType type;
        private final String keyId;
        private final String value;

        public Credential(CredentialType type, String value) {
            this(type, null, value);
        }

        public Credential(CredentialType type, String keyId, String value) {
            this.type = type;
            this.keyId = keyId;
            this.value = value;
        }
    }

    @Getter
    @EqualsAndHashCode
    @ToString
    public static final class Role {
        private final String id;
        private final List<Grant> grants;
        private final List<String> grantReferences;

        public Role(String id, List<Grant> grants, List<String> grantReferences) {
            this.id = id;
            this.grants = copyGrants(grants);
            this.grantReferences = List.copyOf(listOrEmpty(grantReferences));
        }
    }

    @Getter
    @EqualsAndHashCode
    @ToString
    public static final class Grant {
        private final String id;
        private final List<GrantExpression> info;

        public Grant(String id, List<GrantExpression> info) {
            this.id = id;
            this.info = copyGrantExpressions(info);
        }
    }

    @Getter
    @EqualsAndHashCode
    @ToString
    public static final class GrantExpression {
        private final GrantKey key;
        private final String value;

        public GrantExpression(GrantKey key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public enum CredentialType {
        SHA1, MD5, PLAIN, OPENSSH_PUBLIC_KEY, SHA3_256, ARGON2, JWT_SIGNING_PUBLIC_KEY;
    }

    public enum GrantKey {
        REPOSITORY, BRANCH, FORCE, READ, WRITE, CREATE, NETWORK_SOURCE, NETWORK_PORT, SHUTDOWN, ADMIN
    }
}
