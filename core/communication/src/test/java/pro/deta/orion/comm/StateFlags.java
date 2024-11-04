package pro.deta.orion.comm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class StateFlags {
    private final AtomicInteger flags = new AtomicInteger(0);

    public void set(ProcessorState state) {
        flags.getAndUpdate(f -> f | state.getBit());
    }

    public void clear(ProcessorState state) {
        flags.getAndUpdate(f -> f & ~state.getBit());
    }

    public boolean isSet(ProcessorState state) {
        return (flags.get() & state.getBit()) != 0;
    }

    public List<ProcessorState> getStates() {
        List<ProcessorState> set = new ArrayList<>();
        for (ProcessorState s : ProcessorState.getAllStates()) {
            if (isSet(s)) {
                set.add(s);
            }
        }
        return set;
    }

    @Override
    public String toString() {
        return String.format("%3s", Integer.toBinaryString(flags.get())).replace(' ', '0');
    }

    public boolean waitFor(ProcessorState state, long timeoutMillis) {
        long endTime = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < endTime) {
            if (isSet(state)) {
                return true;
            }
            LockSupport.parkNanos(5_000);
        }
        return isSet(state);
    }
}
