package pro.deta.orion.transport.git.command;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.command.audit.CommandAuditRecord;
import pro.deta.orion.command.audit.CommandAuditSink;

@Slf4j
@Singleton
public final class Slf4jCommandAuditSink implements CommandAuditSink {
    @Inject
    public Slf4jCommandAuditSink() {}

    @Override
    public void record(CommandAuditRecord record) {
        log.info(format(record));
    }

    static String format(CommandAuditRecord record) {
        return "Orion command audit user=" + record.userId()
                + " request=" + record.requestId()
                + " session=" + record.sessionId()
                + " source=" + record.sourceAddress()
                + " path=" + record.commandPath()
                + " action=" + record.action()
                + " parameters=" + record.parameters()
                + " result=" + record.resultKind() + "/" + record.resultCode()
                + " durationNanos=" + record.durationNanos()
                + " metadata=" + record.auditMetadata();
    }
}
