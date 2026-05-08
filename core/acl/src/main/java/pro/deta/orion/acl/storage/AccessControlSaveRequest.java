package pro.deta.orion.acl.storage;

import pro.deta.orion.internal.UserEmail;

import java.util.Objects;

public record AccessControlSaveRequest(String message, UserEmail author) {
    public AccessControlSaveRequest {
        message = Objects.requireNonNullElse(message, "");
        author = Objects.requireNonNullElse(author, UserEmail.EMPTY);
    }
}
