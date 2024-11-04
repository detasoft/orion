package pro.deta.orion.comm;

import java.util.ArrayList;
import java.util.List;

public class ProcessorState {
    private static int nextBit = 1;
    private static final List<ProcessorState> allStates = new ArrayList<>();

    public static final ProcessorState INIT_COMPLETED = new ProcessorState("INIT_COMPLETED");
    public static final ProcessorState READ_COMPLETED = new ProcessorState("READ_COMPLETED");
    public static final ProcessorState WRITE_COMPLETED = new ProcessorState("WRITE_COMPLETED");

    private final int bit;
    private final String name;

    private ProcessorState(String name) {
        this.name = name;
        this.bit = nextBit;
        nextBit <<= 1;
        allStates.add(this);
    }

    public int getBit() {
        return bit;
    }

    public String getName() {
        return name;
    }

    public static List<ProcessorState> getAllStates() {
        return allStates;
    }

    @Override
    public String toString() {
        return name;
    }
}