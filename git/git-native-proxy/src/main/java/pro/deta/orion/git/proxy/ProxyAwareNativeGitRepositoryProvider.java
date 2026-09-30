package pro.deta.orion.git.proxy;

import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.decision.ConnectionFailureHandler;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.git.client.GitSshClientTransport.HostKeyRejectedException;
import java.util.function.BiFunction;
import pro.deta.orion.decision.DecisionRequiredException;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.Connection;
import pro.deta.orion.schema.orion.ConnectionReference;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.io.IOException;
import pro.deta.orion.git.client.GitFileClientTransport;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

@Singleton
public final class ProxyAwareNativeGitRepositoryProvider implements NativeGitRepositoryProvider {
    @Override
    public synchronized void close() {
            for (ProxyNativeGitRepository repository : provisionalBindings.values()) repository.revoke();
            for (ProxyNativeGitRepository repository : activeBindings.values()) repository.revoke();
        try {
            backend.close();
        } finally {
            provisionalBindings.clear();
            provisionalSources.clear();
            activeBindings = Map.of();
        }
    }

    private final NativeGitRepositoryProvider backend;
    private final BootstrapGitTransportFactory transportFactory;
    private final BootstrapSecretResolver secretResolver;
    private final BootstrapGitFetcher fetcher;
    private final BootstrapGitPusher pusher;
    private final ConcurrentMap<String, ProxyNativeGitRepository> provisionalBindings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> provisionalSources = new ConcurrentHashMap<>();
    private volatile Map<String, ProxyNativeGitRepository> activeBindings = Map.of();
    private volatile Map<RemoteAlias, ProxyNativeGitRepository> bootstrapOverrides = Map.of();
    private volatile boolean activePhase;
    private ConnectionFailureHandler connectionFailures;
    private BiFunction<ProxySshConnection, HostKeyRejectedException, Decision> hostKeyDecisions;

    @Inject
    public ProxyAwareNativeGitRepositoryProvider(
            @Named("nativeRepositoryBackend") NativeGitRepositoryProvider backend) {
        this(
                backend,
                new BootstrapSecretResolver(System.getenv()),
                new NativeBootstrapGitFetcher(),
                new NativeBootstrapGitPusher());
    }

    public static ProxyAwareNativeGitRepositoryProvider bootstrap(
            NativeGitRepositoryProvider backend,
            Map<String, String> environment) {
        return new ProxyAwareNativeGitRepositoryProvider(
                backend,
                new BootstrapSecretResolver(environment),
                new NativeBootstrapGitFetcher(),
                new NativeBootstrapGitPusher());
    }

    ProxyAwareNativeGitRepositoryProvider(
            NativeGitRepositoryProvider backend,
            BootstrapSecretResolver secretResolver,
            BootstrapGitFetcher fetcher,
            BootstrapGitPusher pusher) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        transportFactory = new BootstrapGitTransportFactory(
                this.secretResolver, this::bootstrapSections);
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.pusher = Objects.requireNonNull(pusher, "pusher");
    }

    public synchronized void connectionFailures(ConnectionFailureHandler handler,
            BiFunction<ProxySshConnection, HostKeyRejectedException, Decision> hostKeyDecisions) {
        if (activePhase) throw new IllegalStateException("Connection failure handler must precede activation");
        connectionFailures = Objects.requireNonNull(handler, "connection failure handler");
        this.hostKeyDecisions = Objects.requireNonNull(hostKeyDecisions, "host key decisions");
    }

    public synchronized ResolvedBootstrapSource resolveProvisional(
            String sourceId,
            BootstrapSourceConfig source,
            boolean allowMissing) {
        if (activePhase) {
            throw new IllegalStateException("Bootstrap source resolution requires the provisional phase");
        }
        String id = requireSourceId(sourceId);
        Objects.requireNonNull(source, "source");
        String location = Objects.requireNonNull(source.getLocation(), "location");
        List<String> paths = repositoryPaths(source);
        if (BootstrapRepositorySources.CONFIGURATION.equals(id)) {
            ResourceLocation parsed = ResourceLocation.parse(location, "ACL repository");
            if (parsed.scheme() instanceof ResourceScheme.File || parsed.scheme() instanceof ResourceScheme.Empty) {
                if (parsed.uri().getRawAuthority() != null || parsed.uri().getRawQuery() != null
                        || parsed.uri().getRawFragment() != null) {
                    throw new IllegalArgumentException("ACL file location must contain only a local repository path");
                }
                Path directory = Path.of(parsed.pathOrSchemeSpecificPart("ACL repository path is required"))
                        .toAbsolutePath().normalize();
                try {
                    GitFileClientTransport.openOrInitialize(directory, allowMissing);
                } catch (IOException failure) {
                    throw new IllegalStateException("Cannot initialize external ACL repository", failure);
                }
                BootstrapSourceConfig external = new BootstrapSourceConfig();
                external.setLocation("git+" + directory.toUri());
                external.setRef(source.selectedRef());
                external.setAuth(source.getAuth());
                source = external;
                location = external.getLocation();
            }
        }
        if (!BootstrapGitLocation.isRemote(location) && !location.startsWith("local:")) {
            return new ResolvedBootstrapSource(
                    id,
                    location,
                    Optional.empty(),
                    refName(source.selectedRef()),
                    paths,
                    Optional.empty(),
                    allowMissing);
        }

        boolean remote = BootstrapGitLocation.isRemote(location);
        BootstrapGitLocation remoteLocation = remote ? BootstrapGitLocation.parse(source) : null;
        boolean sourceAdded = !provisionalSources.containsKey(id);
        String repositoryName = remote
                ? prepareProvisional(id, source)
                : prepareLocal(id, location);
        String refName = remote ? remoteLocation.refName() : refName(source.selectedRef());
        try {
            NativeGitRepository repository = backend.find(repositoryName)
                    .valueOrFailure("Cannot open bootstrap repository");
            if (!repository.refs().containsKey(refName)) {
                if (allowMissing) {
                    return resolved(id, repositoryName, refName, paths, Optional.empty(), allowMissing);
                }
                throw new IllegalStateException("Bootstrap source ref is unavailable: " + id);
            }
            try {
                String revision = repository.refs().get(refName);
                if (revision == null) {
                    throw new GitRepositoryFileNotFoundException("Branch not found: " + refName);
                }
                for (String path : paths) {
                    repository.files().readFile(new pro.deta.orion.git.parser.v2.id.ObjectId(revision), path,
                            (type, size, base, input) -> Boolean.TRUE);
                }
                return resolved(id, repositoryName, refName, paths, Optional.of(revision), allowMissing);
            } catch (GitRepositoryFileNotFoundException error) {
                if (allowMissing && primaryPathIsMissing(repository, refName, paths)) {
                    return resolved(id, repositoryName, refName, paths, Optional.empty(), allowMissing);
                }
                throw new IllegalStateException("Bootstrap source path is unavailable: " + id);
            } catch (IOException | GitOperationException error) {
                throw new IllegalStateException("Bootstrap source path is unavailable: " + id);
            }
        } catch (RuntimeException error) {
            if (sourceAdded) {
                provisionalSources.remove(id, repositoryName);
                if (remote && !provisionalSources.containsValue(repositoryName)) {
                    provisionalBindings.remove(repositoryName);
                }
            }
            throw error;
        }
    }

    public synchronized String prepareProvisional(
            String sourceId,
            BootstrapSourceConfig source) {
        if (activePhase) {
            throw new IllegalStateException("Bootstrap source resolution requires the provisional phase");
        }
        String id = requireSourceId(sourceId);
        BootstrapGitLocation location = BootstrapGitLocation.parse(source);
        ProxyNativeGitRepository bootstrap = provisionalBindings.get("bootstrap");
        String repositoryName = BootstrapRepositorySources.CONFIGURATION.equals(id)
                || bootstrap != null && bootstrap.location().proxyName().equals(location.proxyName())
                ? "bootstrap" : repositoryName(location.proxyName());
        String previousSource = provisionalSources.get(id);
        if (previousSource != null && !previousSource.equals(repositoryName)) {
            throw new IllegalStateException("Bootstrap source binding conflicts");
        }
        ProxyNativeGitRepository previousBinding = provisionalBindings.get(repositoryName);
        if (previousBinding != null && !previousBinding.location().proxyName().equals(location.proxyName())) {
            throw new IllegalStateException("Bootstrap source binding conflicts");
        }
        if (previousBinding != null && !previousBinding.location().isBindingCompatibleWith(location)) {
            throw new IllegalStateException("Bootstrap proxy binding configuration conflicts");
        }
        boolean sourceAdded = previousSource == null;
        provisionalSources.putIfAbsent(id, repositoryName);
        ProxyNativeGitRepository candidate = null;
        try {
            NativeGitRepository repository = findOrCreate(repositoryName);
            candidate = ProxyNativeGitRepository.create(
                    repositoryName, location,
                    repository,
                    transportFactory,
                    fetcher,
                    pusher);
            ProxyNativeGitRepository binding = provisionalBindings.putIfAbsent(repositoryName, candidate);
            if (binding == null) {
                binding = candidate;
            }
            binding.refresh();
            return repositoryName;
        } catch (RuntimeException error) {
            if (sourceAdded) {
                provisionalSources.remove(id, repositoryName);
            }
            if (candidate != null) {
                provisionalBindings.remove(repositoryName, candidate);
            }
            throw error;
        }
    }

    public SyncObservation syncObservation(GitProxyBinding binding, OrionDocument.SystemConfiguration system) {
        Objects.requireNonNull(binding, "proxy binding");
        ProxyNativeGitRepository proxy = bootstrapOverrides.get(binding.alias());
        if (proxy == null) proxy = activeBindings.get(BootstrapGitLocation.persistent(binding, system).proxyName());
        return proxy == null ? new SyncObservation(SyncStatus.NOT_CHECKED, null) : proxy.syncObservation();
    }

    public record SyncObservation(SyncStatus status, Instant observedAt) {
    }

    public enum SyncStatus {
        NOT_CHECKED, SUCCESS, UNAVAILABLE, AUTHENTICATION_FAILED, CONFLICT
    }

    public synchronized Result<SyncObservation> retry(RemoteAlias alias, Supplier<OrionDocument> current,
            ConfigurationSecrets secrets) {
        if (!activePhase) {
            throw new IllegalStateException("Proxy runtime is not active");
        }
        OrionDocument document = current.get();
        GitProxyBinding selected = null;
        for (GitProxyBinding binding : document.system().proxies()) {
            if (binding.alias().equals(alias)) {
                selected = binding;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("Proxy alias is unavailable");
        }
        secrets.validate(document);
        BootstrapGitTransportFactory persistent = BootstrapGitTransportFactory.persistent(
                current, secrets, connectionFailures, hostKeyDecisions);
        Map<String, ProxyNativeGitRepository> previousBindings = activeBindings;
        Map<String, ProxyNativeGitRepository> candidate = new LinkedHashMap<>();
        for (GitProxyBinding binding : document.system().proxies()) {
            BootstrapGitLocation location = BootstrapGitLocation.persistent(binding, document.system());
            ProxyNativeGitRepository runtime = bootstrapOverrides.get(binding.alias());
            if (runtime == null) runtime = candidate.get(location.proxyName());
            if (runtime == null) runtime = previousBindings.get(location.proxyName());
            if (runtime == null) {
                runtime = ProxyNativeGitRepository.create(location.proxyName(), location, findOrCreate(location.proxyName()),
                        persistent, fetcher, pusher);
        ProxyNativeGitRepository bootstrap = previousBindings.get("bootstrap");
        if (bootstrap != null) candidate.put("bootstrap", bootstrap);
            }
            addActiveBinding(candidate, binding, runtime);
        }
        retainDeferredInternalSources(previousBindings, candidate);
        installBindings(candidate);
        ProxyNativeGitRepository runtime = candidate.get(selected.publicRepositoryName());
        try {
            runtime.refresh();
        } catch (BootstrapGitProxyException failure) {
            if (failure.getCause() instanceof RejectedExecutionException) throw failure;
            return new Result.Failure<>(Result.FailureCode.GENERAL, failure.getMessage(), failure);
        }
        return Result.of(runtime.syncObservation());
    }

    public boolean isBootstrapSource(GitProxyBinding binding, BootstrapRepositorySources sources,
            OrionDocument.SystemConfiguration system) {
        ProxyNativeGitRepository runtime = bootstrapOverrides.get(binding.alias());
        return sources.referencesRepository(runtime == null
                ? BootstrapGitLocation.persistent(binding, system).proxyName() : runtime.repositoryName());
    }

    public synchronized void activate(Supplier<OrionDocument> current, ConfigurationSecrets secrets) {
        activate(current, secrets, false);
    }

    public synchronized void activate(Supplier<OrionDocument> current, ConfigurationSecrets secrets,
            boolean retainInternalBootstrapSources) {
        Objects.requireNonNull(current, "current configuration");
        Objects.requireNonNull(secrets, "configuration secrets");
        OrionDocument document = Objects.requireNonNull(current.get(), "configuration");
        secrets.validate(document);
        Map<RemoteAlias, ProxyNativeGitRepository> overrides = new LinkedHashMap<>();
        Map<String, ProxyNativeGitRepository> retainedSources = new LinkedHashMap<>();
        ProxyNativeGitRepository bootstrap = binding("bootstrap");
        if (bootstrap != null) retainedSources.put("bootstrap", bootstrap);
        if (activePhase) {
            for (GitProxyBinding binding : document.system().proxies()) {
                ProxyNativeGitRepository runtime = bootstrapOverrides.get(binding.alias());
                if (runtime != null) overrides.put(binding.alias(), runtime);
            if (bootstrap != null) adopted.add("bootstrap");
            }
        } else {
            Set<String> adopted = new HashSet<>();
            for (GitProxyBinding binding : document.system().proxies()) {
                adopted.add(BootstrapGitLocation.persistent(binding, document.system()).proxyName());
            }
            for (Map.Entry<String, String> source : provisionalSources.entrySet()) {
                if (bootstrapSection(source.getKey()) == null) continue;
                ProxyNativeGitRepository runtime = provisionalBindings.get(source.getValue());
                if (runtime == null || runtime.name().equals("bootstrap")) continue;
                GitProxyBinding binding = sourceBinding(document, source.getKey(), runtime.location());
                if (binding == null) continue;
                adopted.add(runtime.repositoryName());
                if (!bootstrapReplacement(binding, runtime.location(), document.system()).unchanged()) {
                    ProxyNativeGitRepository previous = overrides.putIfAbsent(binding.alias(), runtime);
                    if (previous != null && previous != runtime) {
                        throw new IllegalStateException("Bootstrap sources require distinct proxy aliases");
                    }
                }
            }
            if (!adopted.containsAll(provisionalBindings.keySet())) {
                if (!retainInternalBootstrapSources) {
                    throw new IllegalStateException("Bootstrap proxy sources must be adopted before activation");
                }
                for (Map.Entry<String, ProxyNativeGitRepository> entry : provisionalBindings.entrySet()) {
                    if (!adopted.contains(entry.getKey())) {
                        retainedSources.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        BootstrapGitTransportFactory persistent = BootstrapGitTransportFactory.persistent(
                current, secrets, connectionFailures, hostKeyDecisions);
        Map<String, ProxyNativeGitRepository> candidate = new LinkedHashMap<>();
        for (GitProxyBinding configured : document.system().proxies()) {
            BootstrapGitLocation location = BootstrapGitLocation.persistent(configured, document.system());
            ProxyNativeGitRepository runtime = overrides.get(configured.alias());
            if (runtime == null) runtime = candidate.get(location.proxyName());
            if (runtime == null) {
                runtime = ProxyNativeGitRepository.create(location.proxyName(), location,
                        findOrCreate(location.proxyName()), persistent, fetcher, pusher);
                try {
                    runtime.refresh();
                } catch (BootstrapGitProxyException failure) {
                    if (!(failure.getCause() instanceof DecisionRequiredException)) throw failure;
                }
            }
            addActiveBinding(candidate, configured, runtime);
        }
        for (Map.Entry<String, ProxyNativeGitRepository> entry : retainedSources.entrySet()) {
            ProxyNativeGitRepository previous = candidate.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && previous != entry.getValue()) {
                throw new IllegalStateException("Bootstrap cache has conflicting proxy bindings");
            }
        }
        if (activePhase && retainInternalBootstrapSources) {
            retainDeferredInternalSources(activeBindings, candidate);
        }
        installBindings(candidate);
        bootstrapOverrides = Map.copyOf(overrides);
        activePhase = true;
    }

    private void installBindings(Map<String, ProxyNativeGitRepository> candidate) {
        Map<String, ProxyNativeGitRepository> previous = activePhase ? activeBindings : provisionalBindings;
        for (Map.Entry<String, ProxyNativeGitRepository> entry : previous.entrySet()) {
            ProxyNativeGitRepository replacement = candidate.get(entry.getKey());
            ProxyNativeGitRepository existing = entry.getValue();
            if (replacement != null && existing.repositoryName().equals(replacement.repositoryName())) {
                existing.reconfigure(replacement);
                candidate.put(entry.getKey(), existing);
            } else {
                existing.revoke();
            }
        }
        activeBindings = Map.copyOf(candidate);
    }

    private void addActiveBinding(Map<String, ProxyNativeGitRepository> candidate,
            GitProxyBinding configured, ProxyNativeGitRepository runtime) {
        if (backend.exists(configured.publicRepositoryName())) {
            throw new IllegalArgumentException("Proxy endpoint repository already exists");
        }
        if (candidate.containsKey(runtime.repositoryName())) {
            throw new IllegalStateException("Proxy bindings resolve to the same bootstrap repository");
        }
        candidate.put(runtime.repositoryName(), runtime);
        candidate.put(configured.publicRepositoryName(), runtime.named(configured.publicRepositoryName()));
    }

    private void retainDeferredInternalSources(Map<String, ProxyNativeGitRepository> previousBindings,
            Map<String, ProxyNativeGitRepository> candidate) {
        for (Map.Entry<String, ProxyNativeGitRepository> source : provisionalBindings.entrySet()) {
            if (previousBindings.get(source.getKey()) != source.getValue()) {
                continue;
            }
            boolean publicBinding = false;
            for (Map.Entry<String, ProxyNativeGitRepository> active : previousBindings.entrySet()) {
                if (isProxyEndpoint(active.getKey())
                        && active.getValue().repositoryName().equals(source.getValue().repositoryName())) {
                    publicBinding = true;
                    break;
                }
            }
            if (!publicBinding) {
                ProxyNativeGitRepository existing = candidate.putIfAbsent(source.getKey(), source.getValue());
                if (existing != null && existing != source.getValue()) {
                    throw new IllegalStateException("Bootstrap cache has conflicting proxy bindings");
                }
            }
        }
    }

    public synchronized OrionDocument adoptProvisional(OrionDocument document, ConfigurationSecrets secrets) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(secrets, "secrets");
        if (activePhase) throw new IllegalStateException("Bootstrap proxy adoption requires the provisional phase");
        secrets.validate(document);
        OrionDocument candidate = document;
        for (Map.Entry<String, String> source : new java.util.TreeMap<>(provisionalSources).entrySet()) {
            ProxyNativeGitRepository runtime = provisionalBindings.get(source.getValue());
            if (runtime == null || runtime.name().equals("bootstrap")) continue;
            BootstrapGitLocation location = runtime.location();
            boolean sameUpstream = false;
            for (GitProxyBinding binding : candidate.system().proxies()) {
                if (BootstrapGitLocation.persistent(binding, candidate.system()).proxyName().equals(location.proxyName())) {
                    sameUpstream = true;
                }
            }
            if (sameUpstream || bootstrapSection(source.getKey()) != null
                    && sourceBinding(candidate, source.getKey(), location) != null) continue;
            RemoteAlias alias = new RemoteAlias(source.getKey());
            for (GitProxyBinding binding : candidate.system().proxies()) {
                if (binding.alias().equals(alias)) throw new IllegalArgumentException("Bootstrap proxy alias is occupied");
            }
            Optional<String> secret = location.credentialKind() == GitCredentialKind.NONE
                    ? Optional.empty() : Optional.of(alias.value() + "-credential");
            if (secret.isPresent()) {
                try (BootstrapSecret value = secretResolver.resolve("Remote Git credential", location.credentialReference())) {
                    candidate = secrets.createSystem(candidate, secret.orElseThrow(), value.copy());
                }
            }
            List<Connection> connections = new ArrayList<>(candidate.system().connections());
            GitProxyBinding.Source transport;
            if ("ssh".equals(location.remoteUri().getScheme())) {
                String name = alias.value() + "-ssh";
                connections.add(Connection.Ssh.fromUpstream(name, location.remoteUri(), location.credentialKind(),
                        secret, location.knownHosts()));
                transport = new GitProxyBinding.Ssh(new ConnectionReference(ConnectionReference.Scope.SYSTEM, name),
                        location.remoteUri().getRawPath());
            } else {
                transport = new GitProxyBinding.Direct(location.remoteUri(), location.credentialKind(), secret,
                        Optional.ofNullable(location.credentialUsername()));
            }
            List<GitProxyBinding> bindings = new ArrayList<>(candidate.system().proxies());
            bindings.add(new GitProxyBinding(alias, transport, location.refName()));
            OrionDocument.SystemConfiguration system = candidate.system();
            candidate = new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(), system.https(),
                    system.secrets(), bindings, connections), candidate.organizations());
        }
        return candidate;
    }

    public record BootstrapChange(GitProxyBinding previous, GitProxyBinding replacement,
            Optional<Connection.Ssh> previousConnection, Optional<Connection.Ssh> replacementConnection) {
        public boolean unchanged() {
            return previous.equals(replacement) && previousConnection.equals(replacementConnection);
        }
    }

    public synchronized List<BootstrapChange> bootstrapChanges(OrionDocument document) {
        List<BootstrapChange> changes = new ArrayList<>();
        for (GitProxyBinding binding : document.system().proxies()) {
            ProxyNativeGitRepository runtime = bootstrapOverrides.get(binding.alias());
            if (runtime == null) continue;
            BootstrapChange change = bootstrapReplacement(binding, runtime.location(), document.system());
            if (!change.unchanged()) changes.add(change);
        }
        return List.copyOf(changes);
    }

    private BootstrapChange bootstrapReplacement(GitProxyBinding binding, BootstrapGitLocation location,
            OrionDocument.SystemConfiguration system) {
        if (binding.source() instanceof GitProxyBinding.Ssh ssh) {
            Connection.Ssh previous = binding.sshConnection(system);
            Connection.Ssh replacement = Connection.Ssh.fromUpstream(previous.name(), location.remoteUri(),
                    previous.credentialKind(), previous.secret(), transportFactory.knownHosts(location));
            return new BootstrapChange(binding, new GitProxyBinding(binding.alias(),
                    new GitProxyBinding.Ssh(ssh.connection(), location.remoteUri().getRawPath()), location.refName()),
                    Optional.of(previous), Optional.of(replacement));
        }
        GitProxyBinding replacement = new GitProxyBinding(binding.alias(),
                new GitProxyBinding.Direct(location.remoteUri(), binding.credentialKind(system),
                        binding.secret(system), binding.username(system)), location.refName());
        return new BootstrapChange(binding, replacement, Optional.empty(), Optional.empty());
    }

    private static GitProxyBinding sourceBinding(OrionDocument document, String sourceId,
            BootstrapGitLocation location) {
        for (GitProxyBinding binding : document.system().proxies()) {
            if (binding.alias().value().equals(sourceId)) return binding;
        }
        for (GitProxyBinding binding : document.system().proxies()) {
            if (BootstrapGitLocation.persistent(binding, document.system()).proxyName().equals(location.proxyName())) return binding;
        }
        return null;
    }

    private List<String> bootstrapSections(BootstrapGitLocation location) {
        List<String> sections = new ArrayList<>();
        for (Map.Entry<String, String> source : provisionalSources.entrySet()) {
            String section = bootstrapSection(source.getKey());
            ProxyNativeGitRepository repository = provisionalBindings.get(source.getValue());
            if (section != null && repository != null && repository.location().equals(location)) sections.add(section);
        }
        sections.sort(String::compareTo);
        return sections;
    }

    private static String bootstrapSection(String sourceId) {
        return switch (sourceId) {
            case BootstrapRepositorySources.CONFIGURATION -> "accessControl";
            case BootstrapRepositorySources.MATERIAL -> "keyMaterial";
            default -> null;
        };
    }

    private String prepareLocal(String sourceId, String location) {
        String repositoryName = repositoryName(location.substring("local:".length()));
        if (isBootstrapCache(repositoryName)) {
            throw new IllegalArgumentException("Bootstrap cache cannot be used as a local repository");
        }
        String previous = provisionalSources.putIfAbsent(sourceId, repositoryName);
        if (previous != null && !previous.equals(repositoryName)) {
            throw new IllegalStateException("Bootstrap source is already bound: " + sourceId);
        }
        findOrCreate(repositoryName);
        return repositoryName;
    }

    private static ResolvedBootstrapSource resolved(
            String sourceId,
            String repositoryName,
            String refName,
            List<String> paths,
            Optional<String> revision,
            boolean createIfMissing) {
        return new ResolvedBootstrapSource(
                sourceId,
                "local:" + repositoryName,
                Optional.of(repositoryName),
                refName,
                paths,
                revision,
                createIfMissing);
    }

    @Override
    public List<String> repositoryNames() {
        Map<String, ProxyNativeGitRepository> hidden = activePhase ? activeBindings : provisionalBindings;
        java.util.ArrayList<String> visible = new java.util.ArrayList<>();
        for (String repositoryName : backend.repositoryNames()) {
            if (!isBootstrapCache(repositoryName) && !hidden.containsKey(repositoryName)) {
                visible.add(repositoryName);
            }
        }
        return List.copyOf(visible);
    }

    @Override
    public boolean isPublicRepositoryName(String repositoryName) {
        String name = repositoryName(repositoryName);
        return !isBootstrapCache(name) && (!isProxyEndpoint(name) || activeBindings.containsKey(name));
    }

    @Override
    public boolean exists(String repositoryName) {
        String canonicalName = repositoryName(repositoryName);
        ProxyNativeGitRepository proxy = binding(canonicalName);
        if (proxy != null) {
            return backend.exists(proxy.repositoryName());
        }
        return !isBootstrapCache(canonicalName) && !isProxyEndpoint(canonicalName)
                && backend.exists(canonicalName);
    }

    @Override
    public Result<NativeGitRepository> find(String repositoryName) {
        return openRepository(repositoryName);
    }

    @Override
    public Result<NativeGitRepository> create(String repositoryName) {
        String canonicalName = repositoryName(repositoryName);
        if (isBootstrapCache(canonicalName)) {
            return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, "Bootstrap cache is internal");
        }
        if (isProxyEndpoint(canonicalName)) {
            return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, "Proxy endpoints require a binding");
        }
        return backend.create(canonicalName);
    }

    @Override
    public Result<NativeGitRepository> openForRead(String repositoryName) {
        return openRepository(repositoryName);
    }

    @Override
    public Result<NativeGitRepository> openForWrite(String repositoryName) {
        return openRepository(repositoryName);
    }

    private Result<NativeGitRepository> openRepository(String repositoryName) {
        String canonicalName = repositoryName(repositoryName);
        ProxyNativeGitRepository proxy = binding(canonicalName);
        if (proxy == null) {
            if (isBootstrapCache(canonicalName) || isProxyEndpoint(canonicalName)) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Bootstrap binding is unavailable");
            }
            return backend.find(canonicalName);
        }
        proxy.refresh();
        return switch (backend.find(proxy.repositoryName())) {
            case Result.Success<NativeGitRepository> ignored -> new Result.Success<>(proxy);
            case Result.Failure<NativeGitRepository> failure -> failure;
        };
    }

    private static boolean isBootstrapCache(String repositoryName) {
        return repositoryName.equals("bootstrap") || repositoryName.startsWith(BootstrapGitLocation.CACHE_PREFIX);
    }

    private static boolean isProxyEndpoint(String repositoryName) {
        return repositoryName.startsWith(GitProxyBinding.REPOSITORY_PREFIX);
    }

    private ProxyNativeGitRepository binding(String repositoryName) {
        if (activePhase) {
            return activeBindings.get(repositoryName);
        }
        return provisionalBindings.get(repositoryName);
    }

    private NativeGitRepository findOrCreate(String repositoryName) {
        if (backend.exists(repositoryName)) {
            return backend.find(repositoryName).valueOrFailure("Cannot open Git proxy");
        }
        Result<NativeGitRepository> created = backend.create(repositoryName);
        if (created instanceof Result.Failure<NativeGitRepository> failure
                && failure.code() == Result.FailureCode.FILE_ALREADY_EXISTS) {
            return backend.find(repositoryName).valueOrFailure("Cannot open Git proxy");
        }
        return created.valueOrFailure("Cannot create Git proxy");
    }

    private static String requireSourceId(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        return sourceId;
    }

    private static String repositoryName(String value) {
        return RepositoryName.parse(value).value();
    }

    private static String repositoryPath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Bootstrap source path must not be blank");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().equals(".")) {
            throw new IllegalArgumentException("Bootstrap source path must stay inside the repository");
        }
        return path.toString().replace('\\', '/');
    }

    private static List<String> repositoryPaths(BootstrapSourceConfig source) {
        List<String> configured = source instanceof BootstrapConfigurationSourceConfig configuration
                ? configuration.selectedPaths()
                : List.of(source.getPath());
        java.util.ArrayList<String> normalized = new java.util.ArrayList<>();
        for (String path : configured) {
            normalized.add(repositoryPath(path));
        }
        return List.copyOf(normalized);
    }

    private static boolean primaryPathIsMissing(
            NativeGitRepository repository,
            String refName,
            List<String> paths) {
        if (paths.size() == 1) {
            return true;
        }
        try {
            String revision = repository.refs().get(refName);
            if (revision == null) {
                return true;
            }
            repository.files().readFile(new pro.deta.orion.git.parser.v2.id.ObjectId(revision), paths.getFirst(),
                    (type, size, base, input) -> Boolean.TRUE);
            return false;
        } catch (GitRepositoryFileNotFoundException missing) {
            return true;
        } catch (IOException | GitOperationException failure) {
            return false;
        }
    }

    private static String refName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Bootstrap source ref must not be blank");
        }
        return value.startsWith("refs/") ? value : "refs/heads/" + value;
    }
}
