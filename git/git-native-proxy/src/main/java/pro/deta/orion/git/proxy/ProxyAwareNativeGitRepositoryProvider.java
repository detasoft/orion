package pro.deta.orion.git.proxy;

import pro.deta.orion.config.ConfigurationSecrets;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Singleton
public final class ProxyAwareNativeGitRepositoryProvider implements NativeGitRepositoryProvider {
    private final NativeGitRepositoryProvider backend;
    private BootstrapGitTransportFactory transportFactory;
    private BootstrapSecretResolver secretResolver;
    private final BootstrapGitFetcher fetcher;
    private final BootstrapGitPusher pusher;
    private final ConcurrentMap<String, BootstrapGitRuntimeProxy> provisionalBindings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BootstrapGitLocation> provisionalLocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> provisionalSources = new ConcurrentHashMap<>();
    private volatile Map<String, BootstrapGitRuntimeProxy> activeBindings = Map.of();
    private volatile boolean activePhase;

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
                this.secretResolver);
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.pusher = Objects.requireNonNull(pusher, "pusher");
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
                GitRepositoryFileSnapshot snapshot = repository.loadFiles(refName, paths);
                return resolved(id, repositoryName, refName, paths, snapshot.version(), allowMissing);
            } catch (GitRepositoryFileNotFoundException error) {
                if (allowMissing && primaryPathIsMissing(repository, refName, paths)) {
                    return resolved(id, repositoryName, refName, paths, Optional.empty(), allowMissing);
                }
                throw new IllegalStateException("Bootstrap source path is unavailable: " + id);
            } catch (GitOperationException error) {
                throw new IllegalStateException("Bootstrap source path is unavailable: " + id);
            }
        } catch (RuntimeException error) {
            if (sourceAdded) {
                provisionalSources.remove(id, repositoryName);
                if (remote && !provisionalSources.containsValue(repositoryName)) {
                    provisionalBindings.remove(repositoryName);
                    provisionalLocations.remove(repositoryName);
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
        String repositoryName = repositoryName(location.proxyName());
        String previousSource = provisionalSources.get(id);
        if (previousSource != null && !previousSource.equals(repositoryName)) {
            throw new IllegalStateException("Bootstrap source binding conflicts");
        }
        BootstrapGitLocation previousLocation = provisionalLocations.get(repositoryName);
        if (previousLocation != null && !previousLocation.isBindingCompatibleWith(location)) {
            throw new IllegalStateException("Bootstrap proxy binding configuration conflicts");
        }
        boolean sourceAdded = previousSource == null;
        boolean locationAdded = previousLocation == null;
        provisionalSources.putIfAbsent(id, repositoryName);
        provisionalLocations.putIfAbsent(repositoryName, location);
        BootstrapGitRuntimeProxy candidate = null;
        try {
            NativeGitRepository repository = findOrCreate(repositoryName);
            candidate = new BootstrapGitRuntimeProxy(
                    location,
                    repository,
                    transportFactory,
                    fetcher,
                    pusher);
            BootstrapGitRuntimeProxy binding = provisionalBindings.putIfAbsent(repositoryName, candidate);
            if (binding == null) {
                binding = candidate;
            }
            binding.refresh();
            return repositoryName;
        } catch (RuntimeException error) {
            if (sourceAdded) {
                provisionalSources.remove(id, repositoryName);
            }
            if (candidate != null && provisionalBindings.remove(repositoryName, candidate) && locationAdded) {
                provisionalLocations.remove(repositoryName, location);
            } else if (locationAdded && !provisionalBindings.containsKey(repositoryName)) {
                provisionalLocations.remove(repositoryName, location);
            }
            throw error;
        }
    }

    public SyncObservation syncObservation(GitProxyBinding binding) {
        Objects.requireNonNull(binding, "proxy binding");
        BootstrapGitRuntimeProxy proxy = activeBindings.get(BootstrapGitLocation.persistent(binding).proxyName());
        return proxy == null ? new SyncObservation(SyncStatus.NOT_CHECKED, null) : proxy.syncObservation();
    }

    public record SyncObservation(SyncStatus status, Instant observedAt) {
    }

    public enum SyncStatus {
        NOT_CHECKED, SUCCESS, UNAVAILABLE, AUTHENTICATION_FAILED, CONFLICT
    }

    public synchronized SyncObservation retry(RemoteAlias alias, Supplier<OrionDocument> current,
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
        var persistent = BootstrapGitTransportFactory.persistent(current, secrets);
        Map<String, BootstrapGitRuntimeProxy> candidate = new LinkedHashMap<>();
        for (GitProxyBinding binding : document.system().proxies()) {
            BootstrapGitLocation location = BootstrapGitLocation.persistent(binding);
            BootstrapGitRuntimeProxy runtime = candidate.get(location.proxyName());
            if (runtime == null) {
                runtime = activeBindings.get(location.proxyName());
            }
            if (runtime == null) {
                runtime = new BootstrapGitRuntimeProxy(location, findOrCreate(location.proxyName()),
                        persistent, fetcher, pusher);
            }
            addActiveBinding(candidate, binding, runtime);
        }
        activeBindings = Map.copyOf(candidate);
        BootstrapGitRuntimeProxy runtime = candidate.get(BootstrapGitLocation.persistent(selected).proxyName());
        try {
            runtime.refresh();
        } catch (BootstrapGitProxyException failure) {
            // The runtime retains the safe result so the operator can retry after recovery.
        }
        return runtime.syncObservation();
    }

    public boolean isBootstrapSource(GitProxyBinding binding, BootstrapRepositorySources sources) {
        return sources.referencesRepository(BootstrapGitLocation.persistent(binding).proxyName());
    }

    public synchronized void activate(Supplier<OrionDocument> current, ConfigurationSecrets secrets) {
        Objects.requireNonNull(current, "current configuration");
        Objects.requireNonNull(secrets, "configuration secrets");
        OrionDocument document = Objects.requireNonNull(current.get(), "configuration");
        secrets.validate(document);
        Map<String, BootstrapGitLocation> locations = new LinkedHashMap<>();
        for (GitProxyBinding binding : document.system().proxies()) {
            BootstrapGitLocation location = BootstrapGitLocation.persistent(binding);
            locations.put(location.proxyName(), location);
        }
        if (!activePhase && !locations.keySet().containsAll(provisionalLocations.keySet())) {
            throw new IllegalStateException("Bootstrap proxy sources must be adopted before activation");
        }
        BootstrapGitTransportFactory persistent = BootstrapGitTransportFactory.persistent(current, secrets);
        Map<String, BootstrapGitRuntimeProxy> candidate = new LinkedHashMap<>();
        for (GitProxyBinding configured : document.system().proxies()) {
            BootstrapGitLocation location = locations.get(BootstrapGitLocation.persistent(configured).proxyName());
            BootstrapGitRuntimeProxy runtime = candidate.get(location.proxyName());
            if (runtime == null) {
                runtime = new BootstrapGitRuntimeProxy(location,
                        findOrCreate(location.proxyName()), persistent, fetcher, pusher);
                runtime.refresh();
            }
            addActiveBinding(candidate, configured, runtime);
        }
        activeBindings = Map.copyOf(candidate);
        activePhase = true;
        transportFactory = null;
        secretResolver = null;
        provisionalBindings.clear();
        provisionalLocations.clear();
        provisionalSources.clear();
    }

    private void addActiveBinding(Map<String, BootstrapGitRuntimeProxy> candidate,
            GitProxyBinding configured, BootstrapGitRuntimeProxy runtime) {
        if (backend.exists(configured.publicRepositoryName())) {
            throw new IllegalArgumentException("Proxy endpoint repository already exists");
        }
        candidate.put(runtime.repositoryName(), runtime);
        candidate.put(configured.publicRepositoryName(), runtime);
    }

    public synchronized OrionDocument adoptProvisional(OrionDocument document, ConfigurationSecrets secrets) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(secrets, "secrets");
        if (activePhase) {
            throw new IllegalStateException("Bootstrap proxy adoption requires the provisional phase");
        }
        secrets.validate(document);
        Map<String, GitProxyBinding> identities = new LinkedHashMap<>();
        var aliases = new HashSet<RemoteAlias>();
        var secretIds = new HashSet<String>();
        for (GitProxyBinding proxy : document.system().proxies()) {
            identities.put(proxy.upstream().toASCIIString() + "#" + proxy.ref(), proxy);
            aliases.add(proxy.alias());
        }
        for (var secret : document.system().secrets()) {
            secretIds.add(secret.id());
        }
        Map<GitProxyBinding, BootstrapGitLocation> additions = new LinkedHashMap<>();
        for (var source : new java.util.TreeMap<>(provisionalSources).entrySet()) {
            BootstrapGitLocation location = provisionalLocations.get(source.getValue());
            if (location == null) {
                continue;
            }
            var upstream = GitProxyBinding.canonicalUpstream(location.remoteUri());
            String identity = upstream.toASCIIString() + "#" + location.refName();
            if (identities.containsKey(identity)) {
                continue;
            }
            RemoteAlias alias = new RemoteAlias(source.getKey());
            if (!aliases.add(alias)) {
                throw new IllegalArgumentException("Bootstrap proxy alias is already occupied");
            }
            Optional<String> secret = location.credentialKind() == GitCredentialKind.NONE
                    ? Optional.empty() : Optional.of(alias.value() + "-credential");
            if (secret.isPresent() && !secretIds.add(secret.orElseThrow())) {
                throw new IllegalArgumentException("Bootstrap proxy secret identity is already occupied");
            }
            GitProxyBinding binding = new GitProxyBinding(alias, upstream, location.refName(),
                    location.credentialKind(), secret, Optional.ofNullable(location.credentialUsername()),
                    Optional.ofNullable(location.knownHosts()).map(Path::toUri));
            identities.put(identity, binding);
            additions.put(binding, location);
        }
        if (additions.isEmpty()) {
            return document;
        }
        OrionDocument candidate = document;
        List<GitProxyBinding> bindings = new ArrayList<>(document.system().proxies());
        for (var addition : additions.entrySet()) {
            GitProxyBinding binding = addition.getKey();
            if (binding.secret().isPresent()) {
                try (BootstrapSecret value = secretResolver.resolve(
                        "Remote Git credential", addition.getValue().credentialReference())) {
                    candidate = secrets.createSystem(candidate, binding.secret().orElseThrow(), value.copy());
                }
            }
            bindings.add(binding);
        }
        return new OrionDocument(new OrionDocument.SystemConfiguration(candidate.system().accessControl(),
                candidate.system().https(), candidate.system().secrets(), bindings), candidate.organizations());
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
        Map<String, BootstrapGitRuntimeProxy> hidden = activePhase ? activeBindings : provisionalBindings;
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
        BootstrapGitRuntimeProxy proxy = binding(canonicalName);
        if (proxy != null) {
            return backend.exists(proxy.repositoryName());
        }
        return !isBootstrapCache(canonicalName) && !isProxyEndpoint(canonicalName)
                && backend.exists(canonicalName);
    }

    @Override
    public Result<NativeGitRepository> find(String repositoryName) {
        return policyBound(repositoryName);
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
        return policyBound(repositoryName);
    }

    @Override
    public Result<NativeGitRepository> openForWrite(String repositoryName) {
        return policyBound(repositoryName);
    }

    @Override
    public void saveFiles(
            String repositoryName,
            String refName,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        String canonicalName = repositoryName(repositoryName);
        BootstrapGitRuntimeProxy proxy = binding(canonicalName);
        if (proxy == null) {
            NativeGitRepositoryProvider.super.saveFiles(canonicalName, refName, files, message, author);
            return;
        }
        NativeGitRepository repository = backend.find(proxy.repositoryName())
                .valueOrFailure("Cannot open native repository " + canonicalName);
        new PolicyBoundNativeGitRepository(this, canonicalName, repository)
                .saveFiles(refName, files, message, author);
    }

    private Result<NativeGitRepository> policyBound(String repositoryName) {
        String canonicalName = repositoryName(repositoryName);
        BootstrapGitRuntimeProxy proxy = binding(canonicalName);
        if (proxy == null) {
            if (isBootstrapCache(canonicalName) || isProxyEndpoint(canonicalName)) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Bootstrap binding is unavailable");
            }
            return backend.find(canonicalName);
        }
        proxy.refresh();
        return switch (backend.find(proxy.repositoryName())) {
            case Result.Success(NativeGitRepository repository) ->
                    new Result.Success<>(new PolicyBoundNativeGitRepository(
                            this,
                            canonicalName,
                            repository));
            case Result.Failure<NativeGitRepository> failure -> failure;
        };
    }

    BootstrapGitRuntimeProxy requireBinding(String repositoryName, String cacheName) {
        BootstrapGitRuntimeProxy proxy = binding(repositoryName);
        if (proxy == null || !proxy.repositoryName().equals(cacheName)) {
            throw new IllegalStateException("Proxy binding is unavailable");
        }
        return proxy;
    }

    private static boolean isBootstrapCache(String repositoryName) {
        return repositoryName.startsWith(BootstrapGitLocation.CACHE_PREFIX);
    }

    private static boolean isProxyEndpoint(String repositoryName) {
        return repositoryName.startsWith(GitProxyBinding.REPOSITORY_PREFIX);
    }

    private BootstrapGitRuntimeProxy binding(String repositoryName) {
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
            repository.loadFiles(refName, List.of(paths.getFirst()));
            return false;
        } catch (GitRepositoryFileNotFoundException missing) {
            return true;
        } catch (GitOperationException failure) {
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
