package pro.deta.orion.command;

import java.util.Optional;

public enum CommandAction {
    LIST("ls"),
    SHOW("show"),
    ADD("add"),
    REMOVE("rm"),
    ATTACH("attach"),
    MONITOR("monitor");

    private final String value;

    CommandAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<CommandAction> fromValue(String value) {
        for (CommandAction action : values()) {
            if (action.value.equalsIgnoreCase(value)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
