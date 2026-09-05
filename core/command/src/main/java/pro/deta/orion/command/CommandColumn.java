package pro.deta.orion.command;

import java.util.Objects;

public record CommandColumn(String name, Type type) {
    public CommandColumn {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
    }

    public static CommandColumn text(String name) {
        return new CommandColumn(name, Type.TEXT);
    }

    public static CommandColumn number(String name) {
        return new CommandColumn(name, Type.NUMBER);
    }

    public static CommandColumn bool(String name) {
        return new CommandColumn(name, Type.BOOLEAN);
    }

    public boolean accepts(CommandValue value) {
        if (value instanceof CommandValue.NullValue) {
            return true;
        }
        return switch (type) {
            case TEXT -> value instanceof CommandValue.Text;
            case NUMBER -> value instanceof CommandValue.Numeric;
            case BOOLEAN -> value instanceof CommandValue.BooleanValue;
        };
    }

    public enum Type {
        TEXT,
        NUMBER,
        BOOLEAN
    }
}
