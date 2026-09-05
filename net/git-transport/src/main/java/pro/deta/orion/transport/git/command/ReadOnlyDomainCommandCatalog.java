package pro.deta.orion.transport.git.command;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.command.CommandAuthorization;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandHandler;
import pro.deta.orion.command.CommandInvocation;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandQuery;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.resource.ScopedResourceCandidate;
import pro.deta.orion.command.resource.ScopedResourceCatalogResult;
import pro.deta.orion.command.resource.ScopedResourceResolver;
import pro.deta.orion.transport.git.command.read.OperatorDomainSource;
import pro.deta.orion.transport.git.command.read.OperatorDomainViews;
import pro.deta.orion.transport.git.command.read.OperatorQueryResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Singleton
public final class ReadOnlyDomainCommandCatalog {
    private static final Set<String> NO_PARAMETERS = Set.of();
    private static final List<CommandColumn> ORGANIZATION_COLUMNS = List.of(
            CommandColumn.text("id"),
            CommandColumn.text("name"));
    private static final List<CommandColumn> ORGANIZATION_USER_COLUMNS = List.of(
            CommandColumn.text("id"),
            CommandColumn.text("name"),
            CommandColumn.text("principalId"));
    private static final List<CommandColumn> REPOSITORY_COLUMNS = List.of(
            CommandColumn.text("id"),
            CommandColumn.text("name"),
            CommandColumn.text("defaultHead"),
            CommandColumn.number("refCount"));
    private static final List<CommandColumn> SESSION_COLUMNS = List.of(
            CommandColumn.text("id"),
            CommandColumn.text("name"),
            CommandColumn.text("state"),
            CommandColumn.text("ownerId"),
            CommandColumn.text("repositoryName"));
    private static final List<CommandColumn> PROXY_COLUMNS = List.of(
            CommandColumn.text("id"),
            CommandColumn.text("name"),
            CommandColumn.text("state"),
            CommandColumn.text("repositoryName"),
            CommandColumn.text("remote"));
    private static final List<CommandColumn> SERVICE_COLUMNS = List.of(
            CommandColumn.text("id"),
            CommandColumn.text("name"),
            CommandColumn.text("state"),
            CommandColumn.text("computedState"),
            CommandColumn.bool("terminal"));
    private static final List<String> SESSION_STATES = List.of("RUNNING", "COMPLETED");
    private static final List<String> PROXY_STATES = List.of("READY", "FAILED");

    private final OperatorDomainSource source;

    @Inject
    public ReadOnlyDomainCommandCatalog(OperatorDomainSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public CommandNode commandTree() {
        CommandNode repositoryResource = CommandNode.builder()
                .action(definition("show", this::authenticatedNamedUser, this::showRepository))
                .build();
        CommandNode repository = CommandNode.builder()
                .action(listDefinition(
                        "ls", this::authenticatedNamedUser, this::listRepositories, REPOSITORY_COLUMNS))
                .dynamicChild(
                        new ScopedResourceResolver<>(this::repositoryCandidates, true),
                        repositoryResource)
                .build();

        CommandNode organizationResource = CommandNode.builder()
                .child("user", CommandNode.builder()
                        .action(listDefinition(
                                "ls",
                                this::authenticatedNamedUser,
                                this::listOrganizationUsers,
                                ORGANIZATION_USER_COLUMNS))
                        .build())
                .child("repository", CommandNode.builder()
                        .action(listDefinition(
                                "ls",
                                this::authenticatedNamedUser,
                                this::listOrganizationRepositories,
                                REPOSITORY_COLUMNS))
                        .build())
                .build();
        CommandNode organization = CommandNode.builder()
                .action(listDefinition(
                        "ls", this::authenticatedNamedUser, this::listOrganizations, ORGANIZATION_COLUMNS))
                .dynamicChild(
                        new ScopedResourceResolver<>(this::organizationCandidates, true),
                        organizationResource)
                .build();

        CommandNode sessionResource = CommandNode.builder()
                .action(definition("show", this::authenticatedNamedUser, this::showSession))
                .build();
        CommandNode session = CommandNode.builder()
                .action(listDefinition(
                        "ls",
                        this::authenticatedNamedUser,
                        this::listSessions,
                        SESSION_COLUMNS,
                        Map.of("state", SESSION_STATES)))
                .dynamicChild(new ScopedResourceResolver<>(this::sessionCandidates, true), sessionResource)
                .build();

        CommandNode proxy = CommandNode.builder()
                .action(listDefinition(
                        "ls",
                        this::authenticatedNamedUser,
                        this::listProxies,
                        PROXY_COLUMNS,
                        Map.of("state", PROXY_STATES)))
                .build();
        CommandNode system = CommandNode.builder()
                .action(definition("resource", this::applicationAdmin, this::systemResources))
                .child("service", CommandNode.builder()
                        .action(listDefinition(
                                "ls", this::applicationAdmin, this::listServices, SERVICE_COLUMNS))
                        .build())
                .build();

        return CommandNode.builder()
                .action(definition("whoami", this::authenticatedNamedUser, this::whoami))
                .child("repository", repository)
                .child("organization", organization)
                .child("session", session)
                .child("proxy", proxy)
                .child("system", system)
                .build();
    }

    private CommandDefinition definition(
            String action,
            CommandAuthorization authorization,
            CommandHandler handler) {
        return new CommandDefinition(
                action,
                0,
                0,
                NO_PARAMETERS,
                NO_PARAMETERS,
                context -> namedUser(context.securityContext()) != null,
                authorization,
                handler,
                CommandCompletion.none(),
                CommandQuery.none());
    }

    private CommandDefinition listDefinition(
            String action,
            CommandAuthorization authorization,
            CommandHandler handler,
            List<CommandColumn> columns) {
        return listDefinition(action, authorization, handler, columns, Map.of());
    }

    private CommandDefinition listDefinition(
            String action,
            CommandAuthorization authorization,
            CommandHandler handler,
            List<CommandColumn> columns,
            Map<String, List<String>> knownValues) {
        List<String> fields = new ArrayList<>(columns.size());
        for (CommandColumn column : columns) {
            fields.add(column.name());
        }
        return new CommandDefinition(
                action,
                0,
                0,
                NO_PARAMETERS,
                NO_PARAMETERS,
                context -> namedUser(context.securityContext()) != null,
                authorization,
                handler,
                CommandCompletion.none(),
                CommandQuery.enabled(fields, knownValues));
    }

    private AccessDecision authenticatedNamedUser(CommandInvocation invocation) {
        SecurityContext context = invocation.context().securityContext();
        AccessDecision authenticated = SubjectAccessRules.authenticated().evaluate(context);
        if (!authenticated.allowed()) {
            return authenticated;
        }
        return namedUser(context) == null
                ? AccessDecision.deny("named user is required")
                : authenticated;
    }

    private AccessDecision applicationAdmin(CommandInvocation invocation) {
        AccessDecision named = authenticatedNamedUser(invocation);
        if (!named.allowed()) {
            return named;
        }
        return ApplicationAccessRules.admin().evaluate(
                invocation.context().securityContext(),
                ApplicationAdminResource.applicationAdmin());
    }

    private CommandResult whoami(CommandInvocation invocation) {
        return new CommandResult.ObjectValue(fields("userId", userId(invocation)));
    }

    private CommandResult listRepositories(CommandInvocation invocation) {
        return switch (repositoryCandidates(invocation.context(), List.of())) {
            case ScopedResourceCatalogResult.Available<OperatorDomainViews.RepositoryView>(var candidates) ->
                    repositoryRows(allowedValues(candidates));
            case ScopedResourceCatalogResult.Unavailable<OperatorDomainViews.RepositoryView> ignored ->
                    unavailable();
            case ScopedResourceCatalogResult.AccessDenied<OperatorDomainViews.RepositoryView> ignored ->
                    denied();
            case ScopedResourceCatalogResult.Failed<OperatorDomainViews.RepositoryView> ignored -> failed();
        };
    }

    private CommandResult showRepository(CommandInvocation invocation) {
        return repositoryObject(resource(invocation, OperatorDomainViews.RepositoryView.class));
    }

    private CommandResult listOrganizations(CommandInvocation invocation) {
        return switch (organizationCandidates(invocation.context(), List.of())) {
            case ScopedResourceCatalogResult.Available<OperatorDomainViews.OrganizationView>(var candidates) ->
                    organizationRows(allowedValues(candidates));
            case ScopedResourceCatalogResult.Unavailable<OperatorDomainViews.OrganizationView> ignored ->
                    unavailable();
            case ScopedResourceCatalogResult.AccessDenied<OperatorDomainViews.OrganizationView> ignored ->
                    denied();
            case ScopedResourceCatalogResult.Failed<OperatorDomainViews.OrganizationView> ignored -> failed();
        };
    }

    private CommandResult listOrganizationUsers(CommandInvocation invocation) {
        OperatorDomainViews.OrganizationView organization =
                resource(invocation, OperatorDomainViews.OrganizationView.class);
        OperatorQueryResult<List<OperatorDomainViews.UserView>> result =
                source.organizationUsers(organization.id());
        if (result instanceof OperatorQueryResult.Unavailable<?>) {
            return unavailable();
        }
        if (result instanceof OperatorQueryResult.Failed<?>) {
            return failed();
        }
        List<OperatorDomainViews.UserView> users = new ArrayList<>(
                ((OperatorQueryResult.AvailableSnapshot<OperatorDomainViews.UserView>) result).value());
        boolean admin = isAdmin(invocation.context().securityContext());
        String userId = userId(invocation);
        users.removeIf(user -> !user.organizationId().equals(organization.id())
                || !admin && !user.principalId().equals(userId));
        users.sort(Comparator.comparing(OperatorDomainViews.UserView::id));
        List<List<CommandValue>> rows = new ArrayList<>();
        for (OperatorDomainViews.UserView user : users) {
            rows.add(List.of(
                    CommandValue.text(user.id()),
                    value(user.name()),
                    CommandValue.text(user.principalId())));
        }
        return CommandResult.Rows.unqueried(
                ORGANIZATION_USER_COLUMNS,
                rows);
    }

    private CommandResult listOrganizationRepositories(CommandInvocation invocation) {
        OperatorDomainViews.OrganizationView organization =
                resource(invocation, OperatorDomainViews.OrganizationView.class);
        OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> result =
                source.organizationRepositories(organization.id());
        if (result instanceof OperatorQueryResult.Unavailable<?>) {
            return unavailable();
        }
        if (result instanceof OperatorQueryResult.Failed<?>) {
            return failed();
        }
        List<OperatorDomainViews.RepositoryView> repositories = new ArrayList<>();
        for (OperatorDomainViews.RepositoryView repository
                : ((OperatorQueryResult.AvailableSnapshot<OperatorDomainViews.RepositoryView>) result).value()) {
            if (repository.organizationId().filter(organization.id()::equals).isPresent()
                    && canRead(invocation.context().securityContext(), repository.repositoryName())) {
                repositories.add(repository);
            }
        }
        return repositoryRows(repositories);
    }

    private CommandResult listSessions(CommandInvocation invocation) {
        return switch (sessionCandidates(invocation.context(), List.of())) {
            case ScopedResourceCatalogResult.Available<OperatorDomainViews.SessionView>(var candidates) ->
                    sessionRows(allowedValues(candidates));
            case ScopedResourceCatalogResult.Unavailable<OperatorDomainViews.SessionView> ignored ->
                    unavailable();
            case ScopedResourceCatalogResult.AccessDenied<OperatorDomainViews.SessionView> ignored ->
                    denied();
            case ScopedResourceCatalogResult.Failed<OperatorDomainViews.SessionView> ignored -> failed();
        };
    }

    private CommandResult showSession(CommandInvocation invocation) {
        return sessionObject(resource(invocation, OperatorDomainViews.SessionView.class));
    }

    private CommandResult listProxies(CommandInvocation invocation) {
        return switch (proxyCandidates(invocation.context(), List.of())) {
            case ScopedResourceCatalogResult.Available<OperatorDomainViews.ProxyView>(var candidates) ->
                    proxyRows(allowedValues(candidates));
            case ScopedResourceCatalogResult.Unavailable<OperatorDomainViews.ProxyView> ignored ->
                    unavailable();
            case ScopedResourceCatalogResult.AccessDenied<OperatorDomainViews.ProxyView> ignored ->
                    denied();
            case ScopedResourceCatalogResult.Failed<OperatorDomainViews.ProxyView> ignored -> failed();
        };
    }

    private CommandResult systemResources(CommandInvocation invocation) {
        return switch (source.systemResources()) {
            case OperatorQueryResult.AvailableValue<OperatorDomainViews.SystemResourceView>(var value) ->
                    new CommandResult.ObjectValue(fields(
                            "availableProcessors", value.availableProcessors(),
                            "heapUsedBytes", value.heapUsedBytes(),
                            "heapCommittedBytes", value.heapCommittedBytes(),
                            "heapMaxBytes", value.heapMaxBytes()));
            case OperatorQueryResult.Unavailable<OperatorDomainViews.SystemResourceView> ignored ->
                    unavailable();
            case OperatorQueryResult.Failed<OperatorDomainViews.SystemResourceView> ignored -> failed();
        };
    }

    private CommandResult listServices(CommandInvocation invocation) {
        return switch (source.services()) {
            case OperatorQueryResult.AvailableSnapshot<OperatorDomainViews.ServiceView>(var services) -> {
                List<OperatorDomainViews.ServiceView> ordered = new ArrayList<>(services);
                ordered.sort(Comparator.comparing(OperatorDomainViews.ServiceView::id));
                List<List<CommandValue>> rows = new ArrayList<>();
                for (OperatorDomainViews.ServiceView service : ordered) {
                    rows.add(List.of(
                            CommandValue.text(service.id()),
                            CommandValue.text(service.name()),
                            CommandValue.text(service.state()),
                            CommandValue.text(service.computedState()),
                            CommandValue.bool(service.terminal())));
                }
                yield CommandResult.Rows.unqueried(SERVICE_COLUMNS, rows);
            }
            case OperatorQueryResult.Unavailable<List<OperatorDomainViews.ServiceView>> ignored ->
                    unavailable();
            case OperatorQueryResult.Failed<List<OperatorDomainViews.ServiceView>> ignored -> failed();
        };
    }

    private ScopedResourceCatalogResult<OperatorDomainViews.RepositoryView> repositoryCandidates(
            pro.deta.orion.command.CommandContext context,
            List<Object> parents) {
        if (namedUser(context.securityContext()) == null) {
            return unnamedIdentityDenied();
        }
        return candidates(
                source.repositories(),
                repository -> repositoryCandidate(context.securityContext(), repository));
    }

    private ScopedResourceCatalogResult<OperatorDomainViews.OrganizationView> organizationCandidates(
            pro.deta.orion.command.CommandContext context,
            List<Object> parents) {
        if (namedUser(context.securityContext()) == null) {
            return unnamedIdentityDenied();
        }
        OperatorQueryResult<List<OperatorDomainViews.OrganizationView>> result = source.organizations();
        if (result
                instanceof OperatorQueryResult.Unavailable<List<OperatorDomainViews.OrganizationView>> value) {
            return new ScopedResourceCatalogResult.Unavailable<>(value.source());
        }
        if (result instanceof OperatorQueryResult.Failed<List<OperatorDomainViews.OrganizationView>> value) {
            return new ScopedResourceCatalogResult.Failed<>(value.source(), value.cause());
        }
        SecurityContext security = context.securityContext();
        boolean admin = isAdmin(security);
        List<ScopedResourceCandidate<OperatorDomainViews.OrganizationView>> candidates = new ArrayList<>();
        OperatorQueryResult.AvailableSnapshot<OperatorDomainViews.OrganizationView> available =
                (OperatorQueryResult.AvailableSnapshot<OperatorDomainViews.OrganizationView>) result;
        for (OperatorDomainViews.OrganizationView organization : available.value()) {
            if (admin) {
                candidates.add(new ScopedResourceCandidate<>(
                        organization.id(),
                        organization.name(),
                        organization,
                        AccessDecision.allow("application admin")));
                continue;
            }
            OperatorQueryResult<List<OperatorDomainViews.UserView>> users =
                    source.organizationUsers(organization.id());
            ScopedResourceCatalogResult<OperatorDomainViews.OrganizationView> userFailure =
                    organizationFailure(users);
            if (userFailure != null) {
                return userFailure;
            }
            OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> repositories =
                    source.organizationRepositories(organization.id());
            ScopedResourceCatalogResult<OperatorDomainViews.OrganizationView> repositoryFailure =
                    organizationFailure(repositories);
            if (repositoryFailure != null) {
                return repositoryFailure;
            }
            boolean allowed = isMember(
                    organization,
                    ((OperatorQueryResult.AvailableSnapshot<OperatorDomainViews.UserView>) users).value(),
                    namedUser(security));
            if (!allowed) {
                allowed = hasReadableRepository(
                        security,
                        organization,
                        ((OperatorQueryResult.AvailableSnapshot<OperatorDomainViews.RepositoryView>) repositories)
                                .value());
            }
            AccessDecision decision = decision(allowed, "organization access");
            candidates.add(new ScopedResourceCandidate<>(
                    organization.id(), organization.name(), organization, decision));
        }
        candidates.sort(Comparator.comparing(ScopedResourceCandidate::id));
        return new ScopedResourceCatalogResult.Available<>(candidates);
    }

    private static ScopedResourceCatalogResult<OperatorDomainViews.OrganizationView> organizationFailure(
            OperatorQueryResult<?> result) {
        if (result instanceof OperatorQueryResult.Unavailable<?> unavailable) {
            return new ScopedResourceCatalogResult.Unavailable<>(unavailable.source());
        }
        if (result instanceof OperatorQueryResult.Failed<?> failed) {
            return new ScopedResourceCatalogResult.Failed<>(failed.source(), failed.cause());
        }
        return null;
    }

    private static boolean isMember(
            OperatorDomainViews.OrganizationView organization,
            List<OperatorDomainViews.UserView> users,
            String principal) {
        for (OperatorDomainViews.UserView user : users) {
            if (user.organizationId().equals(organization.id()) && user.principalId().equals(principal)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasReadableRepository(
            SecurityContext context,
            OperatorDomainViews.OrganizationView organization,
            List<OperatorDomainViews.RepositoryView> repositories) {
        for (OperatorDomainViews.RepositoryView repository : repositories) {
            if (repository.organizationId().filter(organization.id()::equals).isPresent()
                    && canRead(context, repository.repositoryName())) {
                return true;
            }
        }
        return false;
    }

    private ScopedResourceCatalogResult<OperatorDomainViews.SessionView> sessionCandidates(
            pro.deta.orion.command.CommandContext context,
            List<Object> parents) {
        String userId = namedUser(context.securityContext());
        if (userId == null) {
            return unnamedIdentityDenied();
        }
        return candidates(source.sessions(), session -> {
            boolean allowed = isAdmin(context.securityContext())
                    || session.ownerId().equals(userId)
                    || session.repositoryName()
                            .filter(name -> canRead(context.securityContext(), name))
                            .isPresent();
            return new ScopedResourceCandidate<>(
                    session.id(), session.name(), session, decision(allowed, "session access"));
        });
    }

    private ScopedResourceCatalogResult<OperatorDomainViews.ProxyView> proxyCandidates(
            pro.deta.orion.command.CommandContext context,
            List<Object> parents) {
        if (namedUser(context.securityContext()) == null) {
            return unnamedIdentityDenied();
        }
        return candidates(source.proxies(), proxy -> {
            boolean allowed = isAdmin(context.securityContext())
                    || proxy.repositoryName()
                            .filter(name -> canRead(context.securityContext(), name))
                            .isPresent();
            return new ScopedResourceCandidate<>(
                    proxy.id(), proxy.name(), proxy, decision(allowed, "proxy access"));
        });
    }

    private ScopedResourceCandidate<OperatorDomainViews.RepositoryView> repositoryCandidate(
            SecurityContext context,
            OperatorDomainViews.RepositoryView repository) {
        boolean allowed = canRead(context, repository.repositoryName());
        return new ScopedResourceCandidate<>(
                repository.id(), repository.name(), repository, decision(allowed, "repository read"));
    }

    private boolean canRead(SecurityContext context, String repositoryName) {
        if (isAdmin(context)) {
            return true;
        }
        return RepositoryAccessRules.read().evaluate(context, RepositoryResource.of(repositoryName)).allowed();
    }

    private static boolean isAdmin(SecurityContext context) {
        return ApplicationAccessRules.admin()
                .evaluate(context, ApplicationAdminResource.applicationAdmin())
                .allowed();
    }

    private static <T> ScopedResourceCatalogResult<T> candidates(
            OperatorQueryResult<List<T>> result,
            Function<T, ScopedResourceCandidate<T>> mapper) {
        if (result instanceof OperatorQueryResult.Unavailable<List<T>> unavailable) {
            return new ScopedResourceCatalogResult.Unavailable<>(unavailable.source());
        }
        if (result instanceof OperatorQueryResult.Failed<List<T>> failed) {
            return new ScopedResourceCatalogResult.Failed<>(failed.source(), failed.cause());
        }
        List<ScopedResourceCandidate<T>> candidates = new ArrayList<>();
        for (T value : ((OperatorQueryResult.AvailableSnapshot<T>) result).value()) {
            candidates.add(mapper.apply(value));
        }
        candidates.sort(Comparator.comparing(ScopedResourceCandidate::id));
        return new ScopedResourceCatalogResult.Available<>(candidates);
    }

    private static <T> ScopedResourceCatalogResult<T> unnamedIdentityDenied() {
        return new ScopedResourceCatalogResult.AccessDenied<>("named user is required");
    }

    private static <T> List<T> allowedValues(List<ScopedResourceCandidate<T>> candidates) {
        List<T> values = new ArrayList<>();
        for (ScopedResourceCandidate<T> candidate : candidates) {
            if (candidate.accessDecision().allowed()) {
                values.add(candidate.value());
            }
        }
        return values;
    }

    private static CommandResult.Rows repositoryRows(List<OperatorDomainViews.RepositoryView> values) {
        values.sort(Comparator.comparing(OperatorDomainViews.RepositoryView::id));
        List<List<CommandValue>> rows = new ArrayList<>();
        for (OperatorDomainViews.RepositoryView repository : values) {
            rows.add(List.of(
                    CommandValue.text(repository.id()),
                    value(repository.name()),
                    CommandValue.text(repository.defaultHead()),
                    CommandValue.number(repository.refCount())));
        }
        return CommandResult.Rows.unqueried(REPOSITORY_COLUMNS, rows);
    }

    private static CommandResult.ObjectValue repositoryObject(OperatorDomainViews.RepositoryView repository) {
        return new CommandResult.ObjectValue(fields(
                "id", repository.id(),
                "name", value(repository.name()),
                "repositoryName", repository.repositoryName(),
                "defaultHead", repository.defaultHead(),
                "refCount", repository.refCount()));
    }

    private static CommandResult.Rows organizationRows(List<OperatorDomainViews.OrganizationView> values) {
        values.sort(Comparator.comparing(OperatorDomainViews.OrganizationView::id));
        List<List<CommandValue>> rows = new ArrayList<>();
        for (OperatorDomainViews.OrganizationView organization : values) {
            rows.add(List.of(CommandValue.text(organization.id()), value(organization.name())));
        }
        return CommandResult.Rows.unqueried(
                ORGANIZATION_COLUMNS,
                rows);
    }

    private static CommandResult.Rows sessionRows(List<OperatorDomainViews.SessionView> values) {
        values.sort(Comparator.comparing(OperatorDomainViews.SessionView::id));
        List<List<CommandValue>> rows = new ArrayList<>();
        for (OperatorDomainViews.SessionView session : values) {
            rows.add(List.of(
                    CommandValue.text(session.id()),
                    value(session.name()),
                    CommandValue.text(session.state()),
                    CommandValue.text(session.ownerId()),
                    value(session.repositoryName())));
        }
        return CommandResult.Rows.unqueried(SESSION_COLUMNS, rows);
    }

    private static CommandResult.ObjectValue sessionObject(OperatorDomainViews.SessionView session) {
        return new CommandResult.ObjectValue(fields(
                "id", session.id(),
                "name", value(session.name()),
                "state", session.state(),
                "ownerId", session.ownerId(),
                "repositoryName", value(session.repositoryName())));
    }

    private static CommandResult.Rows proxyRows(List<OperatorDomainViews.ProxyView> values) {
        values.sort(Comparator.comparing(OperatorDomainViews.ProxyView::id));
        List<List<CommandValue>> rows = new ArrayList<>();
        for (OperatorDomainViews.ProxyView proxy : values) {
            rows.add(List.of(
                    CommandValue.text(proxy.id()),
                    value(proxy.name()),
                    CommandValue.text(proxy.state()),
                    value(proxy.repositoryName()),
                    CommandValue.text(proxy.remote())));
        }
        return CommandResult.Rows.unqueried(PROXY_COLUMNS, rows);
    }

    private static <T> T resource(CommandInvocation invocation, Class<T> type) {
        return type.cast(invocation.resolvedResources().getLast());
    }

    private static AccessDecision decision(boolean allowed, String reason) {
        return allowed ? AccessDecision.allow(reason) : AccessDecision.deny(reason);
    }

    private static String userId(CommandInvocation invocation) {
        return invocation.context().securityContext().getUserIdentity().getUserId();
    }

    private static String namedUser(SecurityContext context) {
        UserIdentity identity = context.getUserIdentity();
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        String userId = identity.getUserId();
        return userId == null || userId.isBlank() ? null : userId;
    }

    private static CommandValue value(Optional<String> value) {
        return value.<CommandValue>map(CommandValue::text).orElseGet(CommandValue::nullValue);
    }

    private static Map<String, CommandValue> fields(Object... values) {
        LinkedHashMap<String, CommandValue> fields = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            fields.put((String) values[index], commandValue(values[index + 1]));
        }
        return fields;
    }

    private static CommandValue commandValue(Object value) {
        return switch (value) {
            case CommandValue commandValue -> commandValue;
            case Integer integer -> CommandValue.number(integer);
            case Long longValue -> CommandValue.number(longValue);
            case Boolean booleanValue -> CommandValue.bool(booleanValue);
            case String string -> CommandValue.text(string);
            default -> throw new IllegalArgumentException("unsupported command value");
        };
    }

    private static CommandResult.Failure unavailable() {
        return new CommandResult.Failure(
                CommandFailureCode.SERVICE_UNAVAILABLE,
                "Resource service is unavailable",
                List.of());
    }

    private static CommandResult.Failure denied() {
        return new CommandResult.Failure(
                CommandFailureCode.ACCESS_DENIED,
                "Access denied",
                List.of());
    }

    private static CommandResult.Failure failed() {
        return new CommandResult.Failure(
                CommandFailureCode.HANDLER_FAILED,
                "Resource lookup failed",
                List.of());
    }
}
