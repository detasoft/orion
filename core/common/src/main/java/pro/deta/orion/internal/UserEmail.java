package pro.deta.orion.internal;

import lombok.Data;

@Data
public class UserEmail {
    public static final UserEmail EMPTY = new UserEmail("", "");

    private final String username;
    private final String email;
}
