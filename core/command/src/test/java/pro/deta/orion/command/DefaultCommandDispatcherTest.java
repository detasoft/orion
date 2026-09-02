package pro.deta.orion.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.command.resource.ScopedResourceCandidate;
import pro.deta.orion.command.resource.ScopedResourceCatalog;
import pro.deta.orion.command.resource.ScopedResourceResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCommandDispatcherTest {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean invoked = new AtomicBoolean();
    private final DefaultCommandDispatcher dispatcher = new DefaultCommandDispatcher(
            new CommandLineParser(),
            commandTree());

    @Test
    void dispatchesStaticDynamicAndRelativeCommandsWithResolvedPayloads() {
        assertThat(dispatch("/repository ls", CommandPath.root()))
                .isEqualTo(new CommandResult.Rows(List.of("name"), List.of(List.of("alpha"))));
        assertThat(dispatch("alpha1 show", CommandPath.absolute(List.of("repository"))))
                .isEqualTo(new CommandResult.ObjectValue(Map.of("name", "alpha")));
        assertThat(invoked).isTrue();
    }

    @Test
    void returnsEveryFiniteHandlerResultWithoutTransportCoupling() {
        assertThat(dispatch("message", CommandPath.root())).isEqualTo(new CommandResult.Message("ok"));
        assertThat(dispatch("object", CommandPath.root()))
                .isEqualTo(new CommandResult.ObjectValue(Map.of("state", "running")));
        assertThat(dispatch("exit", CommandPath.root())).isEqualTo(new CommandResult.Exit(5, "stopped"));
    }

    @Test
    void reportsUnknownPathsActionsAndInvalidArguments() {
        assertFailure(dispatch("/missing show", CommandPath.root()), CommandFailureCode.UNKNOWN_PATH);
        assertFailure(dispatch("/repository show", CommandPath.root()), CommandFailureCode.UNKNOWN_COMMAND);
        assertFailure(
                dispatch("/repository ls extra", CommandPath.root()),
                CommandFailureCode.INVALID_ARGUMENTS);
        assertFailure(
                dispatch("/repository ls page=2", CommandPath.root()),
                CommandFailureCode.INVALID_ARGUMENTS);
    }

    @Test
    void matchesActionsCaseInsensitivelyWithoutChangingPathCase() {
        assertThat(dispatch("/repository LS", CommandPath.root()))
                .isEqualTo(new CommandResult.Rows(List.of("name"), List.of(List.of("alpha"))));
        assertFailure(dispatch("/Repository ls", CommandPath.root()), CommandFailureCode.UNKNOWN_PATH);
    }

    @Test
    void reportsMissingAndAmbiguousDynamicResources() {
        assertFailure(
                dispatch("/repository/absent show", CommandPath.root()),
                CommandFailureCode.MISSING_RESOURCE);
        CommandResult result = dispatch("/repository/alpha show", CommandPath.root());
        assertThat(result).isEqualTo(new CommandResult.Failure(
                CommandFailureCode.AMBIGUOUS_RESOURCE,
                "Resource selector is ambiguous",
                List.of("alpha1", "alpha2")));
    }

    @Test
    void authorizationIsIndependentOfPresentationVisibility() {
        assertThat(dispatch("hidden", CommandPath.root())).isEqualTo(new CommandResult.Message("visible"));
        assertFailure(dispatch("/repository rm", CommandPath.root()), CommandFailureCode.ACCESS_DENIED);
    }

    @Test
    void cancellationPreventsHandlerInvocation() {
        cancelled.set(true);

        assertFailure(dispatch("message", CommandPath.root()), CommandFailureCode.CANCELLED);
        assertThat(invoked).isFalse();
    }

    @Test
    void convertsHandlerExceptionsWithoutLeakingTheirMessages() {
        CommandResult result = dispatch("explode", CommandPath.root());

        assertFailure(result, CommandFailureCode.HANDLER_FAILED);
        assertThat(((CommandResult.Failure) result).message()).doesNotContain("sensitive failure detail");
    }

    private CommandResult dispatch(String commandLine, CommandPath currentPath) {
        return dispatcher.dispatch(new CommandRequest(commandLine, context(currentPath)));
    }

    private CommandContext context(CommandPath currentPath) {
        return new CommandContext(
                SecurityContext.createContext(),
                "request",
                "session",
                "source",
                currentPath,
                CommandPresentation.plain(),
                cancelled::get,
                Map.of());
    }

    private CommandNode commandTree() {
        CommandNode resourceNode = CommandNode.builder()
                .action(definition("show", 0, 0, Set.of(), invocation -> {
                    invoked.set(true);
                    String name = (String) invocation.resolvedResources().getLast();
                    return new CommandResult.ObjectValue(Map.of("name", name));
                }))
                .build();
        ScopedResourceCatalog<String> catalog = (context, parents) -> List.of(
                candidate("alpha1", "alpha"),
                candidate("alpha2", "another-alpha"),
                candidate("denied", "hidden", false));
        CommandNode repositories = CommandNode.builder()
                .action(definition("ls", 0, 0, Set.of(), invocation ->
                        new CommandResult.Rows(List.of("name"), List.of(List.of("alpha")))))
                .action(new CommandDefinition(
                        "rm",
                        0,
                        0,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        context -> true,
                        invocation -> AccessDecision.deny("test denial"),
                        invocation -> new CommandResult.Message("removed")))
                .dynamicChild(new ScopedResourceResolver<>(catalog, true), resourceNode)
                .build();
        return CommandNode.builder()
                .child("repository", repositories)
                .action(definition("message", 0, 0, Set.of(), invocation -> {
                    invoked.set(true);
                    return new CommandResult.Message("ok");
                }))
                .action(definition("object", 0, 0, Set.of(), invocation ->
                        new CommandResult.ObjectValue(Map.of("state", "running"))))
                .action(definition("exit", 0, 0, Set.of(), invocation ->
                        new CommandResult.Exit(5, "stopped")))
                .action(definition("explode", 0, 0, Set.of(), invocation -> {
                    throw new Exception("sensitive failure detail");
                }))
                .action(new CommandDefinition(
                        "hidden",
                        0,
                        0,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        context -> false,
                        invocation -> AccessDecision.allow("allowed independently"),
                        invocation -> new CommandResult.Message("visible")))
                .build();
    }

    private static CommandDefinition definition(
            String action,
            int minimumArguments,
            int maximumArguments,
            Set<String> namedParameters,
            CommandHandler handler) {
        return new CommandDefinition(
                action,
                minimumArguments,
                maximumArguments,
                namedParameters,
                Set.of(),
                Set.of(),
                context -> true,
                invocation -> AccessDecision.allow("test"),
                handler);
    }

    private static ScopedResourceCandidate<String> candidate(String id, String name) {
        return candidate(id, name, true);
    }

    private static ScopedResourceCandidate<String> candidate(String id, String name, boolean allowed) {
        AccessDecision decision = allowed ? AccessDecision.allow("test") : AccessDecision.deny("test");
        return new ScopedResourceCandidate<>(id, Optional.of(name), name, decision);
    }

    private static void assertFailure(CommandResult result, CommandFailureCode code) {
        assertThat(result).isInstanceOf(CommandResult.Failure.class);
        assertThat(((CommandResult.Failure) result).code()).isEqualTo(code);
    }
}
