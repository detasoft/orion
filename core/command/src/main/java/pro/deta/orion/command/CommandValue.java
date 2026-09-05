package pro.deta.orion.command;

import java.math.BigDecimal;
import java.util.Objects;

public sealed interface CommandValue {
    default String asText() {
        return switch (this) {
            case Text text -> text.value();
            case Numeric numeric -> numeric.value().toPlainString();
            case BooleanValue booleanValue -> Boolean.toString(booleanValue.value());
            case NullValue ignored -> "null";
        };
    }

    static Text text(String value) {
        return new Text(value);
    }

    static Numeric number(long value) {
        return new Numeric(BigDecimal.valueOf(value));
    }

    static Numeric number(BigDecimal value) {
        return new Numeric(value);
    }

    static BooleanValue bool(boolean value) {
        return new BooleanValue(value);
    }

    static NullValue nullValue() {
        return NullValue.INSTANCE;
    }

    record Text(String value) implements CommandValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    record Numeric(BigDecimal value) implements CommandValue {
        public Numeric {
            Objects.requireNonNull(value, "value");
        }
    }

    record BooleanValue(boolean value) implements CommandValue {}

    record NullValue() implements CommandValue {
        private static final NullValue INSTANCE = new NullValue();
    }
}
