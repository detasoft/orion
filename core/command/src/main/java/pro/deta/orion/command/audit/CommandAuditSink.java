package pro.deta.orion.command.audit;

@FunctionalInterface
public interface CommandAuditSink {
    void record(CommandAuditRecord record);
}
