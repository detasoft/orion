package pro.deta.orion.auth;

import lombok.Data;

@Data
public class UserIdentity {
    public static final UserIdentity ANONYMOUS = new UserIdentity(null);
    private final String clientId;

    public boolean isAnonymous() {
        return clientId == null;
    }
}
