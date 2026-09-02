package pro.deta.orion.command.audit;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.schema.acl.AccessControl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AuditingCommandDispatcherTest {
    private final List<CommandAuditRecord> records = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(100);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final DefaultCommandDispatcher base = baseDispatcher();
    private final CommandDispatcher dispatcher = new AuditingCommandDispatcher(
            base,
            base,
            records::add,
            () -> clock.getAndAdd(25));

    @Test
    void recordsIdentityMetadataOutcomeAndRedactedParametersOnce() {
        CommandResult result = dispatcher.dispatch(request("configure public=value secret=top-secret"));

        assertThat(result).isEqualTo(new CommandResult.Message("configured"));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.userId()).isEqualTo("operator");
            assertThat(record.requestId()).isEqualTo("request-1");
            assertThat(record.sessionId()).isEqualTo("session-1");
            assertThat(record.sourceAddress()).isEqualTo("192.0.2.5:2200");
            assertThat(record.commandPath()).isEqualTo("/");
            assertThat(record.action()).isEqualTo("configure");
            assertThat(record.parameters()).containsExactly(
                    Map.entry("public", "value"),
                    Map.entry("secret", "<redacted>"));
            assertThat(record.parameters()).doesNotContainValue("top-secret");
            assertThat(record.resultKind()).isEqualTo("MESSAGE");
            assertThat(record.resultCode()).isEqualTo("SUCCESS");
            assertThat(record.durationNanos()).isEqualTo(25);
            assertThat(record.auditMetadata()).containsExactly(Map.entry("transport", "ssh"));
        });
    }

    @Test
    void recordsExpectedFailureCancellationAndHandlerException() {
        dispatcher.dispatch(request("unknown"));
        cancelled.set(true);
        dispatcher.dispatch(request("configure public=value secret=hidden"));
        cancelled.set(false);
        dispatcher.dispatch(request("explode"));

        assertThat(records).extracting(CommandAuditRecord::resultCode).containsExactly(
                "UNKNOWN_COMMAND",
                "CANCELLED",
                "HANDLER_FAILED");
        assertThat(records).allSatisfy(record -> assertThat(record.durationNanos()).isNotNegative());
    }

    @Test
    void redactsAllNamedValuesWhenTheCommandDefinitionCannotBeResolved() {
        dispatcher.dispatch(request("configur secret=top-secret"));
        dispatcher.dispatch(request("/missing show credential=other-secret"));

        assertThat(records).extracting(CommandAuditRecord::parameters).containsExactly(
                Map.of("secret", "<redacted>"),
                Map.of("credential", "<redacted>"));
        assertThat(records).allSatisfy(record -> assertThat(record.parameters().values())
                .doesNotContain("top-secret", "other-secret"));
    }

    @Test
    void redactsNamedValuesThatAreNotAllowedByTheResolvedDefinition() {
        CommandResult result = dispatcher.dispatch(request("configure secrett=top-secret"));

        assertThat(result).isEqualTo(new CommandResult.Failure(
                CommandFailureCode.INVALID_ARGUMENTS,
                "Unknown named parameter: secrett",
                List.of()));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.parameters()).containsExactly(Map.entry("secrett", "<redacted>"));
            assertThat(record.parameters()).doesNotContainValue("top-secret");
        });
    }

    @Test
    void redactsUnclassifiedPositionalAndWhereValues() {
        dispatcher.dispatch(request("configur unresolved-secret"));
        dispatcher.dispatch(request("configure extra-secret"));
        dispatcher.dispatch(request("configure where password=predicate-secret"));

        assertThat(records).extracting(CommandAuditRecord::parameters).containsExactly(
                Map.of("$0", "<redacted>"),
                Map.of("$0", "<redacted>"),
                Map.of("where.password", "<redacted>"));
        assertThat(records).allSatisfy(record -> assertThat(record.parameters().values())
                .doesNotContain("unresolved-secret", "extra-secret", "predicate-secret"));
        assertThat(records).extracting(CommandAuditRecord::resultCode).containsExactly(
                "UNKNOWN_COMMAND",
                "INVALID_ARGUMENTS",
                "INVALID_ARGUMENTS");
    }

    @Test
    void keepsDeclaredNonSensitivePositionalAndWhereValuesVisible() {
        dispatcher.dispatch(request("inspect visible where state=running"));

        assertThat(records).singleElement().extracting(CommandAuditRecord::parameters).isEqualTo(Map.of(
                "$0", "visible",
                "where.state", "running"));
    }

    @Test
    void capturesAuditIdentityBeforeDispatchCanMutateTheSecurityContext() {
        CommandRequest request = request("configure public=value secret=hidden");
        CommandDispatcher mutatingDelegate = commandRequest -> {
            commandRequest.context().securityContext().withUserIdentity(identity("replacement"));
            return new CommandResult.Message("configured");
        };
        CommandDispatcher audited = new AuditingCommandDispatcher(
                mutatingDelegate,
                base,
                records::add,
                clock::getAndIncrement);

        audited.dispatch(request);

        assertThat(records).singleElement().extracting(CommandAuditRecord::userId).isEqualTo("operator");
        assertThat(request.context().securityContext().getUserIdentity().getUserId()).isEqualTo("replacement");
    }

    @Test
    void auditSinkFailureDoesNotReplaceCommandResult() {
        CommandDispatcher withBrokenSink = new AuditingCommandDispatcher(
                base,
                base,
                record -> {
                    throw new IllegalStateException("unavailable");
                },
                clock::getAndIncrement);

        assertThat(withBrokenSink.dispatch(request("configure public=value secret=hidden")))
                .isEqualTo(new CommandResult.Message("configured"));
    }

    private CommandRequest request(String commandLine) {
        CommandContext context = new CommandContext(
                securityContext(),
                "request-1",
                "session-1",
                "192.0.2.5:2200",
                CommandPath.root(),
                CommandPresentation.plain(),
                cancelled::get,
                Map.of("transport", "ssh"));
        return new CommandRequest(commandLine, context);
    }

    private static DefaultCommandDispatcher baseDispatcher() {
        CommandDefinition configure = new CommandDefinition(
                "configure",
                0,
                0,
                Set.of("public", "secret"),
                Set.of("secret"),
                Set.of(),
                context -> true,
                invocation -> AccessDecision.allow("test"),
                invocation -> new CommandResult.Message("configured"));
        CommandDefinition explode = new CommandDefinition(
                "explode",
                0,
                0,
                Set.of(),
                Set.of(),
                Set.of(),
                context -> true,
                invocation -> AccessDecision.allow("test"),
                invocation -> {
                    throw new Exception("private exception");
                });
        CommandDefinition inspect = new CommandDefinition(
                "inspect",
                0,
                1,
                Set.of(),
                Set.of(),
                Set.of("state"),
                context -> true,
                invocation -> AccessDecision.allow("test"),
                invocation -> new CommandResult.Message("inspected"));
        return new DefaultCommandDispatcher(
                new CommandLineParser(),
                CommandNode.builder().action(configure).action(explode).action(inspect).build());
    }

    private static SecurityContext securityContext() {
        return SecurityContext.createContext().withUserIdentity(identity("operator")).withRequestId("request-1");
    }

    private static UserIdentity identity(String userId) {
        return new UserIdentity() {
            @Override
            public String getUserId() {
                return userId;
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }

            @Override
            public List<AccessControl.Grant> getGrants() {
                return List.of();
            }
        };
    }
}
