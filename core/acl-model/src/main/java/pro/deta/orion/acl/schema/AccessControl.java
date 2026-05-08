package pro.deta.orion.acl.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;

@Data
@RequiredArgsConstructor
public class AccessControl extends CloneToUnmodifiable<AccessControl> {
    public static final String TRUE_STRING = "true";

    private final List<User> users;
    private final List<Role> roles;
    private final List<Grant> grants;

    public AccessControl() {
        users = new ArrayList<>();
        roles = new ArrayList<>();
        grants = new ArrayList<>();
    }

    @Override
    public AccessControl unmodify() {
        return new AccessControl(
                users.stream().map(CloneToUnmodifiable::unmodify).toList(),
                roles.stream().map(CloneToUnmodifiable::unmodify).toList(),
                grants.stream().map(CloneToUnmodifiable::unmodify).toList()
        );
    }

    @Override
    public AccessControl modify() {
        return new AccessControl(
                users.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList()),
                roles.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList()),
                grants.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList())
        );
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    public static final class User extends CloneToUnmodifiable<User> {
        private final String id;
        private final String first;
        private final String last;
        private final String email;
        private final List<Credential> credentials;
        private final List<String> roles;
        private final List<Grant> grants;

        @Override
        public User unmodify() {
            return new User(id, first, last, email,
                    credentials.stream().map(CloneToUnmodifiable::unmodify).toList(),
                    Collections.unmodifiableList(roles),
                    grants.stream().map(CloneToUnmodifiable::unmodify).toList()
            );
        }

        @Override
        public User modify() {
            return new User(id, first, last, email,
                    credentials.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList()),
                    new ArrayList<>(roles),
                    grants.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList())
            );
        }

        public User addCredential(CredentialType credentialType, String credentialValue) {
            getCredentials().add(new Credential(credentialType, credentialValue));
            return this;
        }

        public User addRole(String role) {
            getRoles().add(role);
            return this;
        }

        public Grant addGrant(String grantId) {
            Grant grant = new Grant(grantId, new ArrayList<>());
            getGrants().add(grant);
            return grant;
        }
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    public static final class Credential extends CloneToUnmodifiable<Credential> {
        private final CredentialType type;
        private final String value;

        @Override
        public Credential unmodify() {
            return new Credential(type, value);
        }

        @Override
        public Credential modify() {
            return new Credential(type, value);
        }
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    public static final class Role extends CloneToUnmodifiable<Role> {
        private final String id;
        private final List<Grant> grants;
        private final List<String> grantReferences;

        @Override
        public Role unmodify() {
            return new Role(id, grants.stream().map(CloneToUnmodifiable::unmodify).toList(), Collections.unmodifiableList(grantReferences));
        }

        @Override
        public Role modify() {
            return new Role(id, new ArrayList<>(grants), new ArrayList<>(grantReferences));
        }

        public AccessControl.Role addGrant(Grant grant) {
            getGrants().add(grant);
            return this;
        }

        public AccessControl.Role addGrantReference(String grant) {
            getGrantReferences().add(grant);
            return this;
        }
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    public static final class Grant extends CloneToUnmodifiable<Grant> {
        private final String id;
        private final List<GrantExpression> info;

        @Override
        public Grant unmodify() {
            return new Grant(id, info.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList()));
        }

        @Override
        public Grant modify() {
            return new Grant(id, new ArrayList<>(info));
        }

        public Grant addKey(GrantKey grantKey, String value) {
            getInfo().add(new GrantExpression(grantKey, value));
            return this;
        }
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    public static class GrantExpression extends CloneToUnmodifiable<GrantExpression> {
        private final GrantKey key;
        private final String value;

        @Override
        public GrantExpression unmodify() {
            return this;
        }

        @Override
        public GrantExpression modify() {
            return this;
        }
    }


    public enum CredentialType {
        SHA1, MD5, PLAIN, OPENSSH_PUBLIC_KEY, SHA3_256, ARGON2, BEARER_TOKEN;
    }

    public enum GrantKey {
        REPOSITORY, BRANCH, FORCE, READ, WRITE, CREATE, NETWORK_SOURCE, NETWORK_PORT, SHUTDOWN, ADMIN
    }

    @SuppressWarnings("rawtypes")
    public static Class<CloneToUnmodifiable>[] getAccessControlClasses() {
        return new Class[] {
                AccessControl.class,
                User.class,
                Role.class,
                Grant.class,
                Credential.class,
        };
    }

}
