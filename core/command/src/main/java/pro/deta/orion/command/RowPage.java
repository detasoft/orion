package pro.deta.orion.command;

import java.util.Objects;
import java.util.OptionalInt;

public record RowPage(int number, int size, int matched, OptionalInt next, boolean explicit) {
    public RowPage {
        Objects.requireNonNull(next, "next");
        if (number < 1) {
            throw new IllegalArgumentException("page number must be positive");
        }
        if (size < 1) {
            throw new IllegalArgumentException("page size must be positive");
        }
        if (matched < 0) {
            throw new IllegalArgumentException("matched count must not be negative");
        }
        if (next.isPresent() && next.getAsInt() <= number) {
            throw new IllegalArgumentException("next page must follow the current page");
        }
    }

    public boolean shouldRender() {
        return explicit || number != 1 || next.isPresent();
    }
}
