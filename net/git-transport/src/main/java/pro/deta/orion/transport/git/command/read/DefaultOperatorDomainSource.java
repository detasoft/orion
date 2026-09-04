package pro.deta.orion.transport.git.command.read;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineStatus;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultOperatorDomainSource implements OperatorDomainSource {
    private final NativeGitRepositoryProvider repositoryProvider;
    private final AggregateStateMachine runtimeStateMachine;
    private final RuntimeMetrics runtimeMetrics;

    @Inject
    public DefaultOperatorDomainSource(
            NativeGitRepositoryProvider repositoryProvider,
            @Named("runtime") AggregateStateMachine runtimeStateMachine,
            RuntimeMetrics runtimeMetrics) {
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.runtimeStateMachine = Objects.requireNonNull(runtimeStateMachine, "runtimeStateMachine");
        this.runtimeMetrics = Objects.requireNonNull(runtimeMetrics, "runtimeMetrics");
    }

    @Override
    public OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> repositories() {
        try {
            List<OperatorDomainViews.RepositoryView> repositories = new ArrayList<>();
            for (String repositoryName : repositoryProvider.repositoryNames()) {
                Result<NativeGitRepository> result = repositoryProvider.find(repositoryName);
                if (result instanceof Result.Failure<NativeGitRepository> failure) {
                    return new OperatorQueryResult.Failed<>("repository", cause(failure));
                }
                NativeGitRepository repository = ((Result.Success<NativeGitRepository>) result).value();
                repositories.add(new OperatorDomainViews.RepositoryView(
                        pathToken(repositoryName),
                        alias(repositoryName),
                        repositoryName,
                        repository.defaultHead(),
                        repository.refs().size(),
                        Optional.empty()));
            }
            repositories.sort(java.util.Comparator.comparing(OperatorDomainViews.RepositoryView::id));
            return new OperatorQueryResult.AvailableSnapshot<>(repositories);
        } catch (RuntimeException failure) {
            return new OperatorQueryResult.Failed<>("repository", failure);
        }
    }

    @Override
    public OperatorQueryResult<List<OperatorDomainViews.OrganizationView>> organizations() {
        return new OperatorQueryResult.Unavailable<>("organization");
    }

    @Override
    public OperatorQueryResult<List<OperatorDomainViews.UserView>> organizationUsers(String organizationId) {
        requireId(organizationId);
        return new OperatorQueryResult.Unavailable<>("organization");
    }

    @Override
    public OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> organizationRepositories(
            String organizationId) {
        requireId(organizationId);
        return new OperatorQueryResult.Unavailable<>("organization");
    }

    @Override
    public OperatorQueryResult<List<OperatorDomainViews.SessionView>> sessions() {
        return new OperatorQueryResult.Unavailable<>("session");
    }

    @Override
    public OperatorQueryResult<List<OperatorDomainViews.ProxyView>> proxies() {
        return new OperatorQueryResult.Unavailable<>("proxy");
    }

    @Override
    public OperatorQueryResult<OperatorDomainViews.SystemResourceView> systemResources() {
        try {
            return new OperatorQueryResult.AvailableValue<>(runtimeMetrics.resources());
        } catch (RuntimeException failure) {
            return new OperatorQueryResult.Failed<>("system-resource", failure);
        }
    }

    @Override
    public OperatorQueryResult<List<OperatorDomainViews.ServiceView>> services() {
        try {
            List<OperatorDomainViews.ServiceView> services = new ArrayList<>();
            StateMachineStatus root = runtimeStateMachine.status();
            flatten(
                    runtimeStateMachine.machine(runtimeStateMachine.name()),
                    root,
                    hierarchyToken(root.name()),
                    services);
            services.sort(java.util.Comparator.comparing(OperatorDomainViews.ServiceView::id));
            return new OperatorQueryResult.AvailableSnapshot<>(services);
        } catch (RuntimeException failure) {
            return new OperatorQueryResult.Failed<>("service", failure);
        }
    }

    private static void flatten(
            StateMachine machine,
            StateMachineStatus status,
            String id,
            List<OperatorDomainViews.ServiceView> services) {
        services.add(new OperatorDomainViews.ServiceView(
                id,
                machine.name(),
                status.state().name(),
                status.computedState().name(),
                status.terminal()));
        for (Map.Entry<String, StateMachineStatus> child : status.children().entrySet()) {
            flatten(
                    machine.directChild(child.getKey()),
                    child.getValue(),
                    id + "." + hierarchyToken(child.getKey()),
                    services);
        }
    }

    private static Optional<String> alias(String value) {
        if (value.equals(".") || value.equals("..") || value.indexOf('/') >= 0) {
            return Optional.empty();
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\''
                    || character == '"'
                    || character == '\\'
                    || Character.isWhitespace(character)
                    || Character.isISOControl(character)) {
                return Optional.empty();
            }
        }
        return Optional.of(value);
    }

    private static String pathToken(String value) {
        if (value.equals(".") || value.equals("..")) {
            return encodeToken(value, false);
        }
        return encodeToken(value, true);
    }

    private static String hierarchyToken(String value) {
        return encodeToken(value, false);
    }

    private static String encodeToken(String value, boolean allowDot) {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int valueByte = item & 0xff;
            if (isUnreserved(valueByte, allowDot)) {
                encoded.append((char) valueByte);
            } else {
                encoded.append('%');
                encoded.append(hex(valueByte >>> 4));
                encoded.append(hex(valueByte & 0xf));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int value, boolean allowDot) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '_'
                || allowDot && value == '.'
                || value == '~';
    }

    private static char hex(int value) {
        return "0123456789ABCDEF".charAt(value);
    }

    private static Throwable cause(Result.Failure<?> failure) {
        if (failure.throwable() != null) {
            return failure.throwable();
        }
        return new IllegalStateException(failure.getMessage());
    }

    private static void requireId(String id) {
        Objects.requireNonNull(id, "organizationId");
        if (id.isBlank()) {
            throw new IllegalArgumentException("organizationId must not be blank");
        }
    }
}
