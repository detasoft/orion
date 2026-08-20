package pro.deta.orion.acl.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessControlDraft {
    private List<User> users = new ArrayList<>();
    private List<Role> roles = new ArrayList<>();
    private List<Grant> grants = new ArrayList<>();

    public static AccessControlDraft from(AccessControl source) {
        AccessControlDraft draft = new AccessControlDraft();
        if (source == null) {
            return draft;
        }
        for (AccessControl.User user : source.getUsers()) {
            draft.getUsers().add(User.from(user));
        }
        for (AccessControl.Role role : source.getRoles()) {
            draft.getRoles().add(Role.from(role));
        }
        for (AccessControl.Grant grant : source.getGrants()) {
            draft.getGrants().add(Grant.from(grant));
        }
        return draft;
    }

    public AccessControl toAccessControl() {
        List<AccessControl.User> accessUsers = new ArrayList<>();
        for (User user : listOrEmpty(users)) {
            if (user != null) {
                accessUsers.add(user.toAccessControl());
            }
        }

        List<AccessControl.Role> accessRoles = new ArrayList<>();
        for (Role role : listOrEmpty(roles)) {
            if (role != null) {
                accessRoles.add(role.toAccessControl());
            }
        }

        List<AccessControl.Grant> accessGrants = new ArrayList<>();
        for (Grant grant : listOrEmpty(grants)) {
            if (grant != null) {
                accessGrants.add(grant.toAccessControl());
            }
        }
        return new AccessControl(accessUsers, accessRoles, accessGrants);
    }

    public void merge(AccessControl source) {
        AccessControlDraft sourceDraft = from(source);
        users.addAll(sourceDraft.getUsers());
        roles.addAll(sourceDraft.getRoles());
        grants.addAll(sourceDraft.getGrants());
    }

    private static <T> List<T> listOrEmpty(List<T> source) {
        if (source == null) {
            return List.of();
        }
        return source;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class User {
        private String id;
        private String first;
        private String last;
        private String email;
        private List<Credential> credentials = new ArrayList<>();
        private List<String> roles = new ArrayList<>();
        private List<Grant> grants = new ArrayList<>();

        public static User from(AccessControl.User source) {
            User user = new User(
                    source.getId(),
                    source.getFirst(),
                    source.getLast(),
                    source.getEmail(),
                    new ArrayList<>(),
                    new ArrayList<>(source.getRoles()),
                    new ArrayList<>());
            for (AccessControl.Credential credential : source.getCredentials()) {
                user.getCredentials().add(Credential.from(credential));
            }
            for (AccessControl.Grant grant : source.getGrants()) {
                user.getGrants().add(Grant.from(grant));
            }
            return user;
        }

        public AccessControl.User toAccessControl() {
            List<AccessControl.Credential> accessCredentials = new ArrayList<>();
            for (Credential credential : listOrEmpty(credentials)) {
                if (credential != null) {
                    accessCredentials.add(credential.toAccessControl());
                }
            }
            List<AccessControl.Grant> accessGrants = new ArrayList<>();
            for (Grant grant : listOrEmpty(grants)) {
                if (grant != null) {
                    accessGrants.add(grant.toAccessControl());
                }
            }
            return new AccessControl.User(id, first, last, email, accessCredentials, roles, accessGrants);
        }

        public User addCredential(AccessControl.CredentialType credentialType, String credentialValue) {
            return addCredential(credentialType, null, credentialValue);
        }

        public User addCredential(AccessControl.CredentialType credentialType, String keyId, String credentialValue) {
            getCredentials().add(new Credential(credentialType, keyId, credentialValue));
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class Credential {
        private AccessControl.CredentialType type;
        private String keyId;
        private String value;

        public Credential(AccessControl.CredentialType type, String value) {
            this(type, null, value);
        }

        public static Credential from(AccessControl.Credential source) {
            return new Credential(source.getType(), source.getKeyId(), source.getValue());
        }

        public AccessControl.Credential toAccessControl() {
            return new AccessControl.Credential(type, keyId, value);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class Role {
        private String id;
        private List<Grant> grants = new ArrayList<>();
        private List<String> grantReferences = new ArrayList<>();

        public static Role from(AccessControl.Role source) {
            Role role = new Role(source.getId(), new ArrayList<>(), new ArrayList<>(source.getGrantReferences()));
            for (AccessControl.Grant grant : source.getGrants()) {
                role.getGrants().add(Grant.from(grant));
            }
            return role;
        }

        public AccessControl.Role toAccessControl() {
            List<AccessControl.Grant> accessGrants = new ArrayList<>();
            for (Grant grant : listOrEmpty(grants)) {
                if (grant != null) {
                    accessGrants.add(grant.toAccessControl());
                }
            }
            return new AccessControl.Role(id, accessGrants, grantReferences);
        }

        public Role addGrant(Grant grant) {
            getGrants().add(grant);
            return this;
        }

        public Role addGrantReference(String grant) {
            getGrantReferences().add(grant);
            return this;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class Grant {
        private String id;
        private List<GrantExpression> info = new ArrayList<>();

        public static Grant from(AccessControl.Grant source) {
            Grant grant = new Grant(source.getId(), new ArrayList<>());
            for (AccessControl.GrantExpression expression : source.getInfo()) {
                grant.getInfo().add(GrantExpression.from(expression));
            }
            return grant;
        }

        public AccessControl.Grant toAccessControl() {
            List<AccessControl.GrantExpression> accessInfo = new ArrayList<>();
            for (GrantExpression expression : listOrEmpty(info)) {
                if (expression != null) {
                    accessInfo.add(expression.toAccessControl());
                }
            }
            return new AccessControl.Grant(id, accessInfo);
        }

        public Grant addKey(AccessControl.GrantKey grantKey, String value) {
            getInfo().add(new GrantExpression(grantKey, value));
            return this;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class GrantExpression {
        private AccessControl.GrantKey key;
        private String value;

        public static GrantExpression from(AccessControl.GrantExpression source) {
            return new GrantExpression(source.getKey(), source.getValue());
        }

        public AccessControl.GrantExpression toAccessControl() {
            return new AccessControl.GrantExpression(key, value);
        }
    }
}
