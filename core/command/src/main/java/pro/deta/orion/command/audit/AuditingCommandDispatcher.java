package pro.deta.orion.command.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.deta.orion.command.CommandAuditDescriber;
import pro.deta.orion.command.CommandAuditDescription;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class AuditingCommandDispatcher implements CommandDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditingCommandDispatcher.class);

    private final CommandDispatcher delegate;
    private final CommandAuditDescriber describer;
    private final CommandAuditSink sink;
    private final LongSupplier nanoTime;

    public AuditingCommandDispatcher(
            CommandDispatcher delegate,
            CommandAuditDescriber describer,
            CommandAuditSink sink,
            LongSupplier nanoTime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.describer = Objects.requireNonNull(describer, "describer");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public CommandResult dispatch(CommandRequest request) {
        Objects.requireNonNull(request, "request");
        CommandAuditDescription description = describer.describe(request);
        String userId = request.context().securityContext().getUserIdentity().getUserId();
        long started = nanoTime.getAsLong();
        CommandResult result = delegate.dispatch(request);
        long duration = Math.max(0, nanoTime.getAsLong() - started);
        CommandAuditRecord record = new CommandAuditRecord(
                userId,
                request.context().requestId(),
                request.context().sessionId(),
                request.context().sourceAddress(),
                description.path(),
                description.action(),
                description.parameters(),
                resultKind(result),
                resultCode(result),
                duration,
                request.context().auditMetadata());
        try {
            sink.record(record);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record command audit", exception);
        }
        return result;
    }

    private static String resultKind(CommandResult result) {
        if (result instanceof CommandResult.Message) {
            return "MESSAGE";
        }
        if (result instanceof CommandResult.Rows) {
            return "ROWS";
        }
        if (result instanceof CommandResult.ObjectValue) {
            return "OBJECT";
        }
        if (result instanceof CommandResult.Stream) {
            return "STREAM";
        }
        if (result instanceof CommandResult.Attachment) {
            return "ATTACHMENT";
        }
        if (result instanceof CommandResult.Exit) {
            return "EXIT";
        }
        return "FAILURE";
    }

    private static String resultCode(CommandResult result) {
        if (result instanceof CommandResult.Failure failure) {
            return failure.code().name();
        }
        if (result instanceof CommandResult.Exit exit) {
            return Integer.toString(exit.exitCode());
        }
        return "SUCCESS";
    }
}
