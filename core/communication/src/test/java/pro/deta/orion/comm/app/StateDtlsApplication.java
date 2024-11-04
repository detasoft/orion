package pro.deta.orion.comm.app;

import pro.deta.orion.comm.DtlsApplication;
import pro.deta.orion.comm.ProcessorState;
import pro.deta.orion.comm.StateFlags;

import java.net.InetSocketAddress;
import java.util.List;

public class StateDtlsApplication extends DtlsApplication<InetSocketAddress> {
    private final StateFlags flags = new StateFlags();

    public boolean isSet(ProcessorState state) {
        return flags.isSet(state);
    }

    public void set(ProcessorState state) {
        flags.set(state);
    }

    public String listStates() {
        List<ProcessorState> states = flags.getStates();
        if (states == null || states.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            sb.append(states.get(i).getName()); // or toString()
            if (i < states.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    public boolean waitFor(ProcessorState s) {
        return flags.waitFor(s, 3000);
    }
}
