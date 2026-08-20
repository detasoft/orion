package pro.deta.orion.lifecycle.state;

public final class StandardStateDefinition {
    public static final StateMachineDefinition.State NEW = new StateMachineDefinition.State("NEW");
    public static final StateMachineDefinition.State FIN = new StateMachineDefinition.State("FIN");
    public static final StateMachineDefinition.State ERR = new StateMachineDefinition.State("ERR");
    public static final StateMachineDefinition.State RUNNING = new StateMachineDefinition.State("RUNNING");
    public static final StateMachineDefinition.State DISABLED = new StateMachineDefinition.State("DISABLED");

    private StandardStateDefinition() {
    }

    public static StateMachineDefinition.State state(String name) {
        StateMachineDefinition.State state = new StateMachineDefinition.State(name);
        if (NEW.equals(state)) {
            return NEW;
        }
        if (FIN.equals(state)) {
            return FIN;
        }
        if (ERR.equals(state)) {
            return ERR;
        }
        if (RUNNING.equals(state)) {
            return RUNNING;
        }
        if (DISABLED.equals(state)) {
            return DISABLED;
        }
        return state;
    }
}
