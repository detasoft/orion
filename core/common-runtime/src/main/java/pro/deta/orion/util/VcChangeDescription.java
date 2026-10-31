package pro.deta.orion.util;

import java.util.Objects;

public record VcChangeDescription(VcChangeAuthor author, String message) {
    public static final VcChangeDescription EMPTY = new VcChangeDescription(VcChangeAuthor.EMPTY, "");

    public VcChangeDescription {
        author = Objects.requireNonNullElse(author, VcChangeAuthor.EMPTY);
        message = message == null || message.isBlank() ? "" : message;
    }
}
