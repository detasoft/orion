package pro.deta.orion.comm.util;

import pro.deta.orion.comm.v3.TimestampedValue;

public class RecentTimestampedValueBuffer<T> extends RecentValueBuffer<TimestampedValue<T>>{

    public RecentTimestampedValueBuffer(int size, T initialValue) {
        super(size, new TimestampedValue<>(initialValue));
    }

    public T putLast(T value) {
        put(new TimestampedValue<>(value));
        return value;
    }

    public T getLast() {
        if (getRecent() == null)
            return null;
        return getRecent().value();
    }
}
