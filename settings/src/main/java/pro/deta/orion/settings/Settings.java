package pro.deta.orion.settings;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.util.Pair;

import java.util.*;

@Data
@RequiredArgsConstructor

public class Settings implements CloneToUnmodifiable<Settings> {
    private final List<User> users;
    private final List<Role> roles;
    private final List<Grant> grants;

    public Settings() {
        users = new ArrayList<>();
        roles = new ArrayList<>();
        grants = new ArrayList<>();
    }

    @Override
    public Settings unmodify() {
        return new Settings(
                users.stream().map(CloneToUnmodifiable::unmodify).toList(),
                roles.stream().map(CloneToUnmodifiable::unmodify).toList(),
                grants.stream().map(CloneToUnmodifiable::unmodify).toList()
        );
    }

    @Data
    public static final class User implements CloneToUnmodifiable<User> {
        private final String id;
        private final String first;
        private final String last;
        private final List<Credential> credentials;
        private final List<String> roles;
        private final List<Grant> grants;


        @Override
        public User unmodify() {
            return new User(id, first, last,
                    credentials.stream().map(CloneToUnmodifiable::unmodify).toList(),
                    Collections.unmodifiableList(roles),
                    grants.stream().map(CloneToUnmodifiable::unmodify).toList()
            );
        }
    }

    @Data
    public static final class Credential implements CloneToUnmodifiable<Credential> {
        private final CredentialType type;
        private final String value;

        @Override
        public Credential unmodify() {
            return new Credential(type, value);
        }
    }

    @Data
    public static final class Role implements CloneToUnmodifiable<Role> {
        private final String id;
        private final List<String> grants;

        @Override
        public Role unmodify() {
            return new Role(id, Collections.unmodifiableList(grants));
        }
    }

    @Data
    public static final class Grant implements CloneToUnmodifiable<Grant> {
        private final String id;
        private final LevelKey level;
        private final Map<GrantKey, String> info;

        @Override
        public Grant unmodify() {
            return new Grant(id, level, Collections.unmodifiableMap(info));
        }
    }

    public enum CredentialType {
        SHA1, MD5, PLAIN, OPENSSH_PUBLIC_KEY, SHA3_256;
    }

    public enum GrantKey {
        REPOSITORY, BRANCH, FORCE, READ_WRITE_CREATE, NETWORK_SOURCE
    }

    public enum LevelKey {
        NETWORK, REPOSITORY, BRANCH
    }

    @SuppressWarnings("rawtypes")
    public static Class<CloneToUnmodifiable>[] getAccessControlClasses() {
        return new Class[] {
                Settings.class,
                User.class,
                Role.class,
                Grant.class,
                Credential.class,
        };
    }

    public void addGrant(String id,LevelKey level, Pair<GrantKey, String>... keys) {
        grants.add(new Grant(id, level, new HashMap<>() {{
            for (Pair<Settings.GrantKey, String> e: keys) {
                this.put(e.getFirst(), e.getSecond());
            }
        }}));
    }
}
