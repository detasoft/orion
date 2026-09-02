package pro.deta.orion.command;

@FunctionalInterface
public interface CommandCancellation {
    boolean isCancelled();

    static CommandCancellation never() {
        return () -> false;
    }
}
