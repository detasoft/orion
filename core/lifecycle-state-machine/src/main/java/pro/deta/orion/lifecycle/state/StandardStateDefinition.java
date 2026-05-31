package pro.deta.orion.lifecycle.state;

public final class StandardStateDefinition {
    public static final StateMachineDefinition.State NEW = new StateMachineDefinition.State("NEW");
    public static final StateMachineDefinition.State FIN = new StateMachineDefinition.State("FIN");
    public static final StateMachineDefinition.State ERR = new StateMachineDefinition.State("ERR");

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
        return state;
    }
}
