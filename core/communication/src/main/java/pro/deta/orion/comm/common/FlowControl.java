package pro.deta.orion.comm.common;

import java.util.function.Consumer;

public sealed interface FlowControl {
    FlowControl CONTINUE = new Continue();
    FlowControl BREAK = new Break();
    FlowControl REPEAT = new Repeat();

    static FlowControl CONTINUE() {
        return CONTINUE;
    }
    static FlowControl BREAK() {
        return BREAK;
    }
    static FlowControl REPEAT() {
        return REPEAT;
    }
    static FlowControl ERROR(Throwable t) {
        return new Error(t);
    }

    default FlowControl onError(Consumer<FlowControl.Error> consumer) {
        if (this instanceof FlowControl.Error e) {
            consumer.accept(e);
        }
        return this;
    }

    default boolean needReturn() {
        if (this == CONTINUE || this == REPEAT) {
            return false;
        } else
            return this == BREAK || this instanceof Error e;
    }

    record Continue() implements FlowControl {};

    record Break() implements FlowControl {};

    record Repeat() implements FlowControl {};

    record Error(Throwable t) implements FlowControl {};

    @FunctionalInterface
    public interface ThrowableSupplier<T> {
        T get() throws Exception;
    }

    static FlowControl runWhileRepeat(ThrowableSupplier<FlowControl> throwableSupplier) {
        FlowControl state;
        do {
            try {
                state = throwableSupplier.get();
            } catch (Exception e) {
                state = ERROR(e);
            }
        } while (state instanceof FlowControl.Repeat);
        return state;
    }
}

