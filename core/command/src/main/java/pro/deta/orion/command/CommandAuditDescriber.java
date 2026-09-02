package pro.deta.orion.command;

@FunctionalInterface
public interface CommandAuditDescriber {
    CommandAuditDescription describe(CommandRequest request);
}
