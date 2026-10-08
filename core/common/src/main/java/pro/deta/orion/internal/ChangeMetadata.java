package pro.deta.orion.internal;

/** Author identity and message describing a persisted change. */
public record ChangeMetadata(UserEmail author, String message) {
    public static final ChangeMetadata EMPTY = new ChangeMetadata(UserEmail.EMPTY, "");
}
