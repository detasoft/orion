package pro.deta.orion.command;

@FunctionalInterface
public interface CommandDispatcher {
    CommandResult dispatch(CommandRequest request);
}
