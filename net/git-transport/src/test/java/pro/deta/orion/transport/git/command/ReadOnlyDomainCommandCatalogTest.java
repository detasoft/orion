package pro.deta.orion.transport.git.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.command.render.PlainCommandRenderer;
import pro.deta.orion.command.terminal.TerminalCommandRenderer;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.transport.git.command.read.OperatorDomainSource;
import pro.deta.orion.transport.git.command.read.OperatorDomainViews;
import pro.deta.orion.transport.git.command.read.OperatorQueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.schema.acl.AccessControl.TRUE_STRING;

class ReadOnlyDomainCommandCatalogTest {
    private final FixtureSource source = new FixtureSource();
    private final CommandNode tree = new ReadOnlyDomainCommandCatalog(source).commandTree();
    private final DefaultCommandDispatcher dispatcher =
            new DefaultCommandDispatcher(new CommandLineParser(), tree);

    @Test
    void rejectsUnnamedIdentitiesBeforeConsultingStaticOrDynamicSources() {
        UserIdentity blank = new InternalUserImpl(" ", List.of());

        assertFailure(dispatch("whoami", SecurityContext.ANONYMOUS), CommandFailureCode.ACCESS_DENIED);
        assertFailure(dispatch("/repository ls", blank), CommandFailureCode.ACCESS_DENIED);
        for (UserIdentity identity : new UserIdentity[]{SecurityContext.ANONYMOUS, null, blank}) {
            assertSanitizedAccessDenied(dispatch("/repository/repo show", identity));
            assertSanitizedAccessDenied(dispatch("/organization/org/user ls", identity));
            assertSanitizedAccessDenied(dispatch("/session/session show", identity));
        }
        assertThat(source.calls).isEmpty();

        assertNoParameters(tree);
    }

    @Test
    void addressesNavigationOperatorRepositoryIdsInDispatchAndCompletion() {
        source.repositories = available(List.of(
                new OperatorDomainViews.RepositoryView(
                        "%2E", Optional.empty(), ".", "refs/heads/main", 2, Optional.empty()),
                new OperatorDomainViews.RepositoryView(
                        "%2E%2E", Optional.empty(), "..", "refs/heads/main", 2, Optional.empty())));
        UserIdentity reader = admin();

        assertThat(dispatch("/repository/%2E show", reader))
                .isEqualTo(repositoryObject("%2E", "", "."));
        assertThat(new CommandNavigator(tree)
                .complete(context(reader), CommandPath.root(), "/repository/%", 13)
                .candidates())
                .containsExactly("%2E/", "%2E%2E/");
    }

    @Test
    void returnsIdentityAndAclFilteredRepositoryRowsAndObjects() {
        source.repositories = available(List.of(
                repository("visible-2", "shared", "team/visible"),
                repository("hidden-1", "hidden", "team/hidden"),
                repository("visible-1", "primary", "team/visible")));
        UserIdentity reader = user("operator", repositoryGrant("team/visible"));

        CommandResult.ObjectValue whoami = (CommandResult.ObjectValue) dispatch("whoami", reader);
        assertThat(whoami.fields()).containsExactly(Map.entry("userId", "operator"));
        assertThat(dispatch("/repository ls", reader)).isEqualTo(new CommandResult.Rows(
                List.of("id", "name", "defaultHead", "refCount"),
                List.of(
                        List.of("visible-1", "primary", "refs/heads/main", "2"),
                        List.of("visible-2", "shared", "refs/heads/main", "2"))));
        assertThat(dispatch("/repository/visible-1 show", reader))
                .isEqualTo(repositoryObject("visible-1", "primary"));
        assertThat(dispatch("/repository/visible- show", reader)).isEqualTo(new CommandResult.Failure(
                CommandFailureCode.AMBIGUOUS_RESOURCE,
                "Resource selector is ambiguous",
                List.of("visible-1", "visible-2")));
        assertThat(dispatch("/repository/primary show", reader))
                .isEqualTo(repositoryObject("visible-1", "primary"));
        assertFailure(dispatch("/repository/hidden-1 show", reader), CommandFailureCode.MISSING_RESOURCE);
        assertThat(((CommandResult.Rows) dispatch("/repository ls", admin())).values()).hasSize(3);

        CommandCompletion.Result completion = new CommandNavigator(tree).complete(
                context(reader), CommandPath.root(), "/repository/h", 13);
        assertThat(completion.candidates()).isEmpty();
    }

    @Test
    void hostileCatalogFieldsAreEscapedForPlainAndInteractivePresentation() {
        String hostile = "evil\r\n\t\u001b\\name";
        source.repositories = available(List.of(repository("hostile", "safe", hostile)));

        String plain = new PlainCommandRenderer()
                .render(dispatch("/repository/hostile show", admin()))
                .stdout();
        String interactive = new TerminalCommandRenderer()
                .render(dispatch("/repository/hostile show", admin()), 200)
                .stdout();

        assertThat(plain).contains("repositoryName=evil\\r\\n\\t\\u001B\\\\name\n");
        assertThat(interactive).contains("evil\\r\\n\\t\\u001B\\\\name");
        assertThat(plain).doesNotContain(hostile);
        assertThat(interactive).doesNotContain(hostile);
    }

    @Test
    void distinguishesEmptyUnavailableAndFailedRepositorySources() {
        source.repositories = available(List.of());
        assertThat(dispatch("/repository ls", user("operator")))
                .isEqualTo(new CommandResult.Rows(
                        List.of("id", "name", "defaultHead", "refCount"), List.of()));

        source.repositories = new OperatorQueryResult.Unavailable<>("repository-secret");
        assertSanitized(dispatch("/repository ls", user("operator")), CommandFailureCode.SERVICE_UNAVAILABLE);
        source.repositories = new OperatorQueryResult.Failed<>(
                "repository-secret", new RuntimeException("sensitive detail"));
        assertSanitized(dispatch("/repository ls", user("operator")), CommandFailureCode.HANDLER_FAILED);
    }

    @Test
    void scopesOrganizationMembersAndRepositoriesWithoutCrossOrganizationNameLeakage() {
        source.organizations = available(List.of(
                new OperatorDomainViews.OrganizationView("org-b", Optional.of("beta")),
                new OperatorDomainViews.OrganizationView("org-a", Optional.of("acme"))));
        source.users.put("org-a", available(List.of(
                userView("member-a", "shared", "org-a", "operator"),
                userView("other-a", "other", "org-a", "other"))));
        source.users.put("org-b", available(List.of(
                userView("member-b", "shared", "org-b", "someone"))));
        source.organizationRepositories.put("org-a", available(List.of(
                repository("repo-a", "shared", "team/readable", "org-a"))));
        source.organizationRepositories.put("org-b", available(List.of(
                repository("repo-b", "shared", "team/hidden", "org-b"))));
        UserIdentity reader = user("operator", repositoryGrant("team/readable"));

        assertThat(dispatch("/organization ls", reader)).isEqualTo(new CommandResult.Rows(
                List.of("id", "name"), List.of(List.of("org-a", "acme"))));
        assertThat(dispatch("/organization/acme/user ls", reader)).isEqualTo(new CommandResult.Rows(
                List.of("id", "name", "principalId"),
                List.of(List.of("member-a", "shared", "operator"))));
        assertThat(dispatch("/organization/org-a/repository ls", reader)).isEqualTo(new CommandResult.Rows(
                List.of("id", "name", "defaultHead", "refCount"),
                List.of(List.of("repo-a", "shared", "refs/heads/main", "2"))));
        assertFailure(dispatch("/organization/org-b/user ls", reader), CommandFailureCode.MISSING_RESOURCE);

        assertThat(dispatch("/organization/org-b/user ls", admin())).isEqualTo(new CommandResult.Rows(
                List.of("id", "name", "principalId"),
                List.of(List.of("member-b", "shared", "someone"))));
    }

    @Test
    void propagatesUnavailableOrganizationDataInsteadOfTreatingItAsEmpty() {
        source.organizations = new OperatorQueryResult.Unavailable<>("organization");

        assertSanitized(dispatch("/organization ls", user("operator")), CommandFailureCode.SERVICE_UNAVAILABLE);
        assertSanitized(
                dispatch("/organization/anything/user ls", user("operator")),
                CommandFailureCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void filtersSessionsAndProxiesBeforeRowsAndResolution() {
        source.sessions = available(List.of(
                session("owned", "same", "operator", Optional.empty()),
                session("readable", "same", "other", Optional.of("team/readable")),
                session("hidden", "same", "other", Optional.of("team/hidden"))));
        source.proxies = available(List.of(
                proxy("readable", Optional.of("team/readable")),
                proxy("hidden", Optional.of("team/hidden")),
                proxy("unassociated", Optional.empty())));
        UserIdentity reader = user("operator", repositoryGrant("team/readable"));

        assertThat(dispatch("/session ls", reader)).isEqualTo(new CommandResult.Rows(
                List.of("id", "name", "state", "ownerId", "repositoryName"),
                List.of(
                        List.of("owned", "same", "RUNNING", "operator", ""),
                        List.of("readable", "same", "RUNNING", "other", "team/readable"))));
        assertThat(dispatch("/session/read show", reader)).isEqualTo(new CommandResult.ObjectValue(Map.of(
                "id", "readable",
                "name", "same",
                "state", "RUNNING",
                "ownerId", "other",
                "repositoryName", "team/readable")));
        assertFailure(dispatch("/session/hidden show", reader), CommandFailureCode.MISSING_RESOURCE);
        assertThat(dispatch("/proxy ls", reader)).isEqualTo(new CommandResult.Rows(
                List.of("id", "name", "state", "repositoryName", "remote"),
                List.of(List.of("readable", "readable-name", "READY", "team/readable", "origin"))));
        assertThat(((CommandResult.Rows) dispatch("/proxy ls", admin())).values()).hasSize(3);
    }

    @Test
    void sanitizesUnavailableAndFailedSessionAndProxySources() {
        source.sessions = new OperatorQueryResult.Unavailable<>("session-internal");
        source.proxies = new OperatorQueryResult.Failed<>(
                "proxy-internal", new RuntimeException("private proxy failure"));

        assertSanitized(dispatch("/session ls", user("operator")), CommandFailureCode.SERVICE_UNAVAILABLE);
        assertSanitized(dispatch("/proxy ls", user("operator")), CommandFailureCode.HANDLER_FAILED);
        assertThat(dispatch("/proxy ls", user("operator")).toString())
                .doesNotContain("proxy-internal", "private proxy failure");
    }

    @Test
    void restrictsSystemResourcesAndServicesToAdministratorsWithStableOrder() {
        source.resources = new OperatorQueryResult.AvailableValue<>(
                new OperatorDomainViews.SystemResourceView(4, 10, 20, 30));
        source.services = available(List.of(
                new OperatorDomainViews.ServiceView("zeta", "Zeta", "NEW", "NEW", false),
                new OperatorDomainViews.ServiceView("alpha", "Alpha", "RUNNING", "RUNNING", true)));

        assertFailure(dispatch("/system resource", user("operator")), CommandFailureCode.ACCESS_DENIED);
        assertFailure(dispatch("/system/service ls", user("operator")), CommandFailureCode.ACCESS_DENIED);
        assertThat(source.calls).isEmpty();
        CommandResult.ObjectValue resources = (CommandResult.ObjectValue) dispatch("/system resource", admin());
        assertThat(resources.fields()).containsExactly(
                Map.entry("availableProcessors", "4"),
                Map.entry("heapUsedBytes", "10"),
                Map.entry("heapCommittedBytes", "20"),
                Map.entry("heapMaxBytes", "30"));
        assertThat(dispatch("/system/service ls", admin())).isEqualTo(new CommandResult.Rows(
                List.of("id", "name", "state", "computedState", "terminal"),
                List.of(
                        List.of("alpha", "Alpha", "RUNNING", "RUNNING", "true"),
                        List.of("zeta", "Zeta", "NEW", "NEW", "false"))));
    }

    @Test
    void returnsEqualStructuredResultsForPlainExecAndInteractivePresentation() {
        source.repositories = available(List.of(repository("project", "project", "project")));
        UserIdentity reader = user("operator", repositoryGrant("project"));

        CommandResult plain = dispatcher.dispatch(new CommandRequest(
                "/repository ls",
                context(reader, CommandPresentation.plain())));
        CommandResult interactive = dispatcher.dispatch(new CommandRequest(
                "/repository ls",
                context(reader, new CommandPresentation(true, true, 80))));

        assertThat(interactive).isEqualTo(plain);
    }

    private CommandResult dispatch(String line, UserIdentity identity) {
        return dispatcher.dispatch(new CommandRequest(line, context(identity)));
    }

    private static CommandContext context(UserIdentity identity) {
        return context(identity, CommandPresentation.plain());
    }

    private static CommandContext context(UserIdentity identity, CommandPresentation presentation) {
        return new CommandContext(
                SecurityContext.createContext().withUserIdentity(identity),
                "request",
                "session",
                "source",
                CommandPath.root(),
                presentation,
                CommandCancellation.never(),
                Map.of());
    }

    private static UserIdentity user(String id, AccessControl.Grant... grants) {
        return new InternalUserImpl(id, List.of(grants));
    }

    private static UserIdentity admin() {
        return user("admin", grant(AccessControl.GrantKey.ADMIN, TRUE_STRING));
    }

    private static AccessControl.Grant repositoryGrant(String repository) {
        return grant(AccessControl.GrantKey.REPOSITORY, repository);
    }

    private static AccessControl.Grant grant(AccessControl.GrantKey key, String value) {
        return new AccessControlDraft.Grant("test", new ArrayList<>()).addKey(key, value).toAccessControl();
    }

    private static OperatorDomainViews.RepositoryView repository(
            String id,
            String name,
            String repositoryName) {
        return repository(id, name, repositoryName, null);
    }

    private static OperatorDomainViews.RepositoryView repository(
            String id, String name, String repositoryName, String organizationId) {
        return new OperatorDomainViews.RepositoryView(
                id,
                Optional.of(name),
                repositoryName,
                "refs/heads/main",
                2,
                Optional.ofNullable(organizationId));
    }

    private static OperatorDomainViews.UserView userView(
            String id, String name, String organizationId, String principalId) {
        return new OperatorDomainViews.UserView(id, Optional.of(name), organizationId, principalId);
    }

    private static OperatorDomainViews.SessionView session(
            String id, String name, String owner, Optional<String> repository) {
        return new OperatorDomainViews.SessionView(id, Optional.of(name), "RUNNING", owner, repository);
    }

    private static OperatorDomainViews.ProxyView proxy(String id, Optional<String> repository) {
        return new OperatorDomainViews.ProxyView(id, Optional.of(id + "-name"), "READY", repository, "origin");
    }

    private static CommandResult.ObjectValue repositoryObject(String id, String name) {
        return repositoryObject(id, name, "team/visible");
    }

    private static CommandResult.ObjectValue repositoryObject(
            String id, String name, String repositoryName) {
        return new CommandResult.ObjectValue(Map.of(
                "id", id,
                "name", name,
                "repositoryName", repositoryName,
                "defaultHead", "refs/heads/main",
                "refCount", "2"));
    }

    private static <T> OperatorQueryResult<List<T>> available(List<T> values) {
        return new OperatorQueryResult.AvailableSnapshot<>(values);
    }

    private static void assertFailure(CommandResult result, CommandFailureCode code) {
        assertThat(result).isInstanceOf(CommandResult.Failure.class);
        assertThat(((CommandResult.Failure) result).code()).isEqualTo(code);
    }

    private static void assertSanitized(CommandResult result, CommandFailureCode code) {
        assertFailure(result, code);
        assertThat(result.toString()).doesNotContain("repository-secret", "sensitive detail");
    }

    private static void assertSanitizedAccessDenied(CommandResult result) {
        assertThat(result).isEqualTo(new CommandResult.Failure(
                CommandFailureCode.ACCESS_DENIED,
                "Access denied",
                List.of()));
        assertThat(result.toString()).doesNotContain("named user is required");
    }

    private static void assertNoParameters(CommandNode node) {
        for (var definition : node.actions().values()) {
            assertThat(definition.allowedNamedParameters()).isEmpty();
            assertThat(definition.allowedWhereFields()).isEmpty();
        }
        for (CommandNode child : node.children().values()) {
            assertNoParameters(child);
        }
        if (node.dynamicChild() != null) {
            assertNoParameters(node.dynamicChild().node());
        }
    }

    private static final class FixtureSource implements OperatorDomainSource {
        private final List<String> calls = new ArrayList<>();
        private final Map<String, OperatorQueryResult<List<OperatorDomainViews.UserView>>> users =
                new java.util.HashMap<>();
        private final Map<String, OperatorQueryResult<List<OperatorDomainViews.RepositoryView>>>
                organizationRepositories = new java.util.HashMap<>();
        private OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> repositories =
                available(List.of());
        private OperatorQueryResult<List<OperatorDomainViews.OrganizationView>> organizations =
                available(List.of());
        private OperatorQueryResult<List<OperatorDomainViews.SessionView>> sessions = available(List.of());
        private OperatorQueryResult<List<OperatorDomainViews.ProxyView>> proxies = available(List.of());
        private OperatorQueryResult<OperatorDomainViews.SystemResourceView> resources =
                new OperatorQueryResult.AvailableValue<>(
                        new OperatorDomainViews.SystemResourceView(1, 0, 0, 0));
        private OperatorQueryResult<List<OperatorDomainViews.ServiceView>> services = available(List.of());

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> repositories() {
            calls.add("repositories");
            return repositories;
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.OrganizationView>> organizations() {
            calls.add("organizations");
            return organizations;
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.UserView>> organizationUsers(
                String organizationId) {
            calls.add("users:" + organizationId);
            return users.getOrDefault(organizationId, available(List.of()));
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> organizationRepositories(
                String organizationId) {
            calls.add("repositories:" + organizationId);
            return organizationRepositories.getOrDefault(organizationId, available(List.of()));
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.SessionView>> sessions() {
            calls.add("sessions");
            return sessions;
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.ProxyView>> proxies() {
            calls.add("proxies");
            return proxies;
        }

        @Override
        public OperatorQueryResult<OperatorDomainViews.SystemResourceView> systemResources() {
            calls.add("resources");
            return resources;
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.ServiceView>> services() {
            calls.add("services");
            return services;
        }
    }
}
