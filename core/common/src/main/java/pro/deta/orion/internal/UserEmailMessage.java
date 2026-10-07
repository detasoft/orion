package pro.deta.orion.internal;

public record UserEmailMessage(String username, String email, String message) {
    public static final UserEmailMessage EMPTY = new UserEmailMessage("", "", "");
}
