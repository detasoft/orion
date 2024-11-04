package pro.deta.orion.comm.util;

import java.util.concurrent.atomic.AtomicInteger;

public class RecentValueBuffer<T> {
    private final T[] _data;
    private final int size;
    private final AtomicInteger idx = new AtomicInteger();

    public RecentValueBuffer(int size, T initialValue) {
        //noinspection unchecked
        _data = (T[]) new Object[size];
        this.size = size;
        put(initialValue);
    }

    public void put(T value) {
        while (true) {
            int cur = current();
            int next = nextIndex(cur);
            if (next(cur, next)) {
                _data[cur] = value;
                return;
            }
        }
    }

    public T getRecent() {
        int ix = prevIndex(current());
        T value = _data[ix];
        int numTries = 0;

        while (value == null && numTries++ < size) {
            ix = prevIndex(ix);
            value = _data[ix];
        }
        return value;
    }

    private int prevIndex(int i) {
        return (i - 1 + size) % size;
    }

    public int nextIndex(int i) {
        return (i + 1 ) % size;
    }

    public int current() {
        return idx.get();
    }

    public boolean next(int prev, int next) {
        return idx.compareAndSet(prev, next);
    }
}
