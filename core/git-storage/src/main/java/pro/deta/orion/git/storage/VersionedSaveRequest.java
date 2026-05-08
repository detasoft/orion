package pro.deta.orion.git.storage;

import pro.deta.orion.internal.UserEmail;

public record VersionedSaveRequest(String message, UserEmail author) {
}
