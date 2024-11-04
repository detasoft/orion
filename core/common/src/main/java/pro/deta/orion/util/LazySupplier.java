package pro.deta.orion.util;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.util.function.Supplier;

@RequiredArgsConstructor
@ToString
public class LazySupplier<T> {
    private volatile T cachedValue = null;
    private final Supplier<T> supplier;

    public T value() {
        if (cachedValue == null) {
            synchronized (this) {
                if (cachedValue == null) {
                    cachedValue = supply();
                }
            }
        }
        return cachedValue;
    }
    private T supply() {
        return supplier.get();
    }
}
