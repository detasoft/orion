package pro.deta.orion.auth;

import lombok.ToString;
import pro.deta.orion.acl.schema.AccessControl;

import java.util.List;
import java.util.Objects;

/**
 * Describes the subject and metadata of one request. This is intentionally separate from protected resources:
 * the context answers "who is doing this and under which request id", while resources answer "what is being accessed".
 */
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


    private UserIdentity userIdentity = ANONYMOUS;
    private String requestId;

    public static SecurityContext createContext() {
        return new SecurityContext();
    }

    public SecurityContext withRequestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    public String getRequestId() {
        return requestId;
    }

    public String formatShort() {
        return userIdentity.toString();
    }

    public UserIdentity getUserIdentity() {
        return userIdentity;
    }

    public SecurityContext withUserIdentity(UserIdentity userIdentity) {
        this.userIdentity = Objects.requireNonNullElse(userIdentity, ANONYMOUS);
        return this;
    }
}
