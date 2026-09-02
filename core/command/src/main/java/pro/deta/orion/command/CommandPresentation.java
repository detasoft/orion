package pro.deta.orion.command;

public record CommandPresentation(boolean interactive, boolean ansi, int terminalColumns) {
    public CommandPresentation {
        if (terminalColumns < 0) {
            throw new IllegalArgumentException("terminalColumns must not be negative");
        }
    }

    public static CommandPresentation plain() {
        return new CommandPresentation(false, false, 0);
    }
}
