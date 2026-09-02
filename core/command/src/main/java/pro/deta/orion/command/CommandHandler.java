package pro.deta.orion.command;

@FunctionalInterface
public interface CommandHandler {
    CommandResult handle(CommandInvocation invocation) throws Exception;
}
