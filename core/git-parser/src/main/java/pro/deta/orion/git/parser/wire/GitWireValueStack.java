package pro.deta.orion.git.parser.wire;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

final class GitWireValueStack {
    private final Deque<Entry<?>> entries = new ArrayDeque<>();

    <T> void push(Class<T> type, T value) {
        entries.push(new Entry<>(
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(value, "value")));
    }

    <T> T pop(Class<T> type) {
        T value = peek(type);
        entries.pop();
        return value;
    }

    <T> T peek(Class<T> type) {
        Class<T> checkedType = Objects.requireNonNull(type, "type");
        Entry<?> entry = entries.peek();
        if (entry == null) {
            throw new IllegalStateException(
                    "Git wire value stack is empty; expected " + checkedType.getSimpleName());
        }
        if (entry.type() != checkedType) {
            throw new IllegalStateException(
                    "Git wire value stack expected " + checkedType.getSimpleName()
                            + " but found " + entry.type().getSimpleName());
        }
        return checkedType.cast(entry.value());
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    private record Entry<T>(Class<T> type, T value) {
    }
}
