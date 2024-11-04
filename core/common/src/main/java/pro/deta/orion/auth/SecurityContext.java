package pro.deta.orion.auth;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.util.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Slf4j
@ToString
public class SecurityContext {
    public static final UserIdentity ANONYMOUS = new UserIdentity() {
        @Override
        public String toString() {
            return "UserIdentity.ANONYMOUS";
        }

        @Override
        public String getUserId() {
            return "";
        }

        @Override
        public boolean isAnonymous() {
            return true;
        }

        @Override
        public List<AccessControl.Grant> getGrants() {
            return List.of();
        }
    };


    private final Map<String, Object> attributes = new HashMap<>();

    public static SecurityContext createContext() {
        return new SecurityContext();
    }

    private SecurityContext() {
        setUserIdentity(ANONYMOUS);
    }

    public <A> SecurityContext with(Permission<A> attribute, A o) {
        return with(attribute.getName(), o);
    }

    public <A> SecurityContext with(String name, A o) {
        if (attributes.containsKey(name))
            log.trace("Attempt to update attribute {} value to [{}]", name, o);
        attributes.put(name, o);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <A> A getAttribute(Permission<A> attribute) {
        return (A) attributes.get(attribute.getName());
    }

    public String formatShort() {
        UserIdentity ui = getUserIdentity();
        if (ui != null)
            return getUserIdentity().toString();
        return "null";
    }

    public UserIdentity getUserIdentity() {
        return getAttribute(Permission.USER_IDENTITY);
    }

    public void setUserIdentity(UserIdentity internalUser) {
        with(Permission.USER_IDENTITY, internalUser);
    }
}
