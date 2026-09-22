package pro.deta.orion.transport.git.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandRowQuery;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.PendingDecision;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionCommandCatalogTest {
    private static final UserIdentity REVIEWER = new InternalUserImpl("reviewer", List.of());
    private static final PrincipalAddress ACTOR = PrincipalAddress.parse("system/reviewer");

    @Test
    void listsScopesAndShowsDescriptionAndAvailableActions() {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> true)) {
            for (String scope : new String[]{null, "acme", "acme/platform", "acme/platform/api"}) {
                register(registry, scope);
            }
            CommandResult.Rows rows = (CommandResult.Rows) dispatch(registry, "/decision ls", REVIEWER);
            assertThat(rows.columns()).extracting(column -> column.name())
                    .containsExactly("id", "scope", "title", "createdAt");
            assertThat(rows.values()).extracting(row -> row.get(1).asText())
                    .containsExactly("system", "acme", "acme/platform", "acme/platform/api");
            assertThat(rows.values()).allSatisfy(row ->
                    assertThat(row.get(2)).isEqualTo(CommandValue.text("SSH host key changed")));

            String id = rows.values().getFirst().getFirst().asText();
            CommandResult.ObjectValue details = (CommandResult.ObjectValue) dispatch(
                    registry, "/decision/" + id + " show", REVIEWER);
            assertThat(details.fields()).containsExactly(
                    Map.entry("id", CommandValue.text(id)),
                    Map.entry("scope", CommandValue.text("system")),
                    Map.entry("title", CommandValue.text("SSH host key changed")),
                    Map.entry("createdAt", rows.values().getFirst().get(3)),
                    Map.entry("description", CommandValue.text("Old fingerprint -> new fingerprint")),
                    Map.entry("action.replace", CommandValue.text("Replace stored key")),
                    Map.entry("action.reject", CommandValue.text("Reject connection")));
        }
    }

    @Test
    void resolvesWithAuthenticatedActorAndRemovesRequestFromCommandsAndCompletion() {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> true)) {
            PendingDecision pending = register(registry, "acme/platform/api");
            String id = pending.request().id().toString();
            CommandNavigator navigator = new CommandNavigator(new DecisionCommandCatalog(registry).commandTree());
            String line = "/decision/";
            assertThat(navigator.complete(context(REVIEWER), CommandPath.root(), line, line.length()).candidates())
                    .contains(id + "/");

            assertThat(dispatch(registry, "/decision/" + id.substring(0, 8) + " resolve replace", REVIEWER))
                    .isEqualTo(new CommandResult.Message("Decision recorded"));
            assertThat(pending.result().toCompletableFuture()).isCompletedWithValue(new Decision("replace", ACTOR));
            assertThat(((CommandResult.Rows) dispatch(registry, "/decision ls", REVIEWER)).values()).isEmpty();
            assertFailure(dispatch(registry, "/decision/" + id + " show", REVIEWER),
                    CommandFailureCode.MISSING_RESOURCE);
            assertFailure(dispatch(registry, "/decision/" + id + " resolve reject", REVIEWER),
                    CommandFailureCode.MISSING_RESOURCE);
            assertThat(navigator.complete(context(REVIEWER), CommandPath.root(), line, line.length()).candidates())
                    .containsExactly("ls");
        }
    }

    @Test
    void invalidActionOrArgumentsLeaveRequestPending() {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> true)) {
            PendingDecision pending = register(registry, null);
            String command = "/decision/" + pending.request().id() + " resolve";
            for (String suffix : List.of("", " unknown", " replace extra", " replace actor=someone", " ''")) {
                assertFailure(dispatch(registry, command + suffix, REVIEWER), CommandFailureCode.INVALID_ARGUMENTS);
                assertThat(pending.result().toCompletableFuture()).isNotDone();
            }
            assertThat(dispatch(registry, command + " reject", REVIEWER))
                    .isEqualTo(new CommandResult.Message("Decision recorded"));
            assertThat(pending.result().toCompletableFuture()).isCompletedWithValue(new Decision("reject", ACTOR));
        }
    }

    @Test
    void rejectsAnonymousAndUnaddressableIdentitiesBeforeConsultingRegistry() {
        AtomicInteger checks = new AtomicInteger();
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> {
            checks.incrementAndGet();
            return true;
        })) {
            PendingDecision pending = register(registry, null);
            for (UserIdentity identity : new UserIdentity[]{SecurityContext.ANONYMOUS, null,
                    new InternalUserImpl(" ", List.of()), new InternalUserImpl("acme/reviewer", List.of())}) {
                assertFailure(dispatch(registry, "/decision ls", identity), CommandFailureCode.ACCESS_DENIED);
                assertFailure(dispatch(registry, "/decision/" + pending.request().id() + " show", identity),
                        CommandFailureCode.ACCESS_DENIED);
                assertFailure(dispatch(registry, "/decision/" + pending.request().id() + " resolve replace", identity),
                        CommandFailureCode.ACCESS_DENIED);
            }
            assertThat(checks).hasValue(0);
            assertThat(pending.result().toCompletableFuture()).isNotDone();
        }
    }

    @Test
    void usesRegistryVisibilityForListingLookupAndCompletion() {
        try (DecisionRegistry registry = new DecisionRegistry(8,
                (actor, scope) -> actor.equals(ACTOR) && scope.isEmpty())) {
            PendingDecision visible = register(registry, null);
            PendingDecision hidden = register(registry, "acme");
            CommandResult.Rows rows = (CommandResult.Rows) dispatch(registry, "/decision ls", REVIEWER);
            assertThat(rows.values()).extracting(row -> row.getFirst().asText())
                    .containsExactly(visible.request().id().toString());
            assertFailure(dispatch(registry, "/decision/" + hidden.request().id() + " show", REVIEWER),
                    CommandFailureCode.MISSING_RESOURCE);
            assertFailure(dispatch(registry, "/decision/" + hidden.request().id() + " resolve replace", REVIEWER),
                    CommandFailureCode.MISSING_RESOURCE);
            CommandNode tree = new DecisionCommandCatalog(registry).commandTree();
            assertThat(new CommandNavigator(tree).complete(context(REVIEWER), CommandPath.root(),
                    "/decision/", 10).candidates())
                    .containsExactlyInAnyOrder(visible.request().id() + "/", "ls");
            assertThat(hidden.result().toCompletableFuture()).isNotDone();
        }
    }

    @Test
    void rechecksRegistryAccessAfterResourceResolution() {
        for (String action : List.of("show", "resolve replace")) {
            AtomicInteger checks = new AtomicInteger();
            try (DecisionRegistry registry = new DecisionRegistry(8,
                    (actor, scope) -> checks.incrementAndGet() == 1)) {
                PendingDecision pending = register(registry, null);
                assertFailure(dispatch(registry, "/decision/" + pending.request().id() + " " + action, REVIEWER),
                        CommandFailureCode.MISSING_RESOURCE);
                assertThat(checks).hasValue(2);
                assertThat(pending.result().toCompletableFuture()).isNotDone();
            }
        }
    }

    private static PendingDecision register(DecisionRegistry registry, String scope) {
        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("replace", "Replace stored key");
        actions.put("reject", "Reject connection");
        return registry.register(Optional.ofNullable(scope).map(ConfigurationScope::parse),
                "SSH host key changed", "Old fingerprint -> new fingerprint", actions)
                .valueOrFailure("register decision");
    }

    private static CommandResult dispatch(DecisionRegistry registry, String line, UserIdentity identity) {
        return new DefaultCommandDispatcher(new CommandLineParser(),
                new DecisionCommandCatalog(registry).commandTree(), new CommandRowQuery())
                .dispatch(new CommandRequest(line, context(identity)));
    }

    private static CommandContext context(UserIdentity identity) {
        return new CommandContext(SecurityContext.createContext().withUserIdentity(identity),
                "request", "session", "source", CommandPath.root(), CommandPresentation.plain(),
                CommandCancellation.never(), Map.of());
    }

    private static void assertFailure(CommandResult result, CommandFailureCode code) {
        assertThat(result).isInstanceOfSatisfying(CommandResult.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
