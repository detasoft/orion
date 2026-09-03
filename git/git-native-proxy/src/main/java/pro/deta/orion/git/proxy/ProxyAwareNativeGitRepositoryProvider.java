package pro.deta.orion.git.proxy;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Singleton
public final class ProxyAwareNativeGitRepositoryProvider implements NativeGitRepositoryProvider {
    private final NativeGitRepositoryProvider backend;
    private final BootstrapGitTransportFactory transportFactory;
    private final BootstrapGitFetcher fetcher;
    private final BootstrapGitPusher pusher;
    private final ConcurrentMap<String, RuntimeGitProxyBinding> provisionalBindings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BootstrapGitLocation> provisionalLocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> provisionalSources = new ConcurrentHashMap<>();
    private volatile Map<String, RuntimeGitProxyBinding> activeBindings = Map.of();
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
        transportFactory = new BootstrapGitTransportFactory(
                Objects.requireNonNull(secretResolver, "secretResolver"));
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.pusher = Objects.requireNonNull(pusher, "pusher");
    }

    public ResolvedBootstrapSource resolveProvisional(
            String sourceId,
            BootstrapSourceConfig source,
            boolean allowMissing) {
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
        String repositoryName = remote
                ? prepareProvisional(id, source)
                : prepareLocal(id, location);
        String refName = remote ? remoteLocation.refName() : refName(source.selectedRef());
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
    }

    public synchronized String prepareProvisional(
            String sourceId,
            BootstrapSourceConfig source) {
        String id = requireSourceId(sourceId);
        BootstrapGitLocation location = BootstrapGitLocation.parse(source);
        String repositoryName = location.proxyName();
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
            RuntimeGitProxyBinding binding = provisionalBindings.putIfAbsent(repositoryName, candidate);
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

    public String provisionalRepositoryName(String sourceId) {
        String repositoryName = provisionalSources.get(requireSourceId(sourceId));
        if (repositoryName == null) {
            throw new IllegalStateException("Bootstrap source has not been resolved: " + sourceId);
        }
        return repositoryName;
    }

    public void activate(
            PersistentProxyCatalog catalog,
            PersistentProxyCredentialResolver credentialResolver) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(credentialResolver, "credentialResolver");
        Map<String, RuntimeGitProxyBinding> loaded = Objects.requireNonNull(
                catalog.load(credentialResolver),
                "catalog bindings");
        Map<String, RuntimeGitProxyBinding> candidate = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, RuntimeGitProxyBinding> entry : loaded.entrySet()) {
            String repositoryName = repositoryName(entry.getKey());
            RuntimeGitProxyBinding binding = Objects.requireNonNull(entry.getValue(), "proxy binding");
            if (!backend.exists(repositoryName)) {
                throw new IllegalStateException("Persistent proxy repository is unavailable: " + repositoryName);
            }
            candidate.put(repositoryName, binding);
        }
        activeBindings = Map.copyOf(candidate);
        activePhase = true;
        provisionalBindings.clear();
        provisionalLocations.clear();
        provisionalSources.clear();
    }

    private String prepareLocal(String sourceId, String location) {
        String repositoryName = repositoryName(location.substring("local:".length()));
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
        Map<String, RuntimeGitProxyBinding> hidden = activePhase ? activeBindings : provisionalBindings;
        java.util.ArrayList<String> visible = new java.util.ArrayList<>();
        for (String repositoryName : backend.repositoryNames()) {
            if (!hidden.containsKey(repositoryName)) {
                visible.add(repositoryName);
            }
        }
        return List.copyOf(visible);
    }

    @Override
    public boolean exists(String repositoryName) {
        return backend.exists(repositoryName);
    }

    @Override
    public Result<NativeGitRepository> find(String repositoryName) {
        return policyBound(repositoryName);
    }

    @Override
    public Result<NativeGitRepository> create(String repositoryName) {
        return backend.create(repositoryName);
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
        RuntimeGitProxyBinding proxy = binding(repositoryName);
        if (proxy == null) {
            NativeGitRepositoryProvider.super.saveFiles(repositoryName, refName, files, message, author);
            return;
        }
        NativeGitRepository repository = backend.find(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName);
        NativeGitFileUpdate update = repository.prepareProxyFileUpdate(refName, files, message, author);
        List<RefUpdateResult> results = proxy.publish(update.objects(), update.refUpdates(), true);
        if (results.contains(RefUpdateResult.STALE)) {
            throw new GitOperationException("Cannot update Git repository: stale ref");
        }
    }

    @Override
    public List<RefUpdateResult> publish(
            String repositoryName,
            LooseObjectStore objects,
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        RuntimeGitProxyBinding proxy = binding(repositoryName);
        if (proxy == null) {
            return NativeGitRepositoryProvider.super.publish(repositoryName, objects, updates, atomic);
        }
        return proxy.publish(objects, updates, atomic);
    }

    private Result<NativeGitRepository> policyBound(String repositoryName) {
        RuntimeGitProxyBinding proxy = binding(repositoryName);
        if (proxy == null) {
            return backend.find(repositoryName);
        }
        proxy.refresh();
        return switch (backend.find(repositoryName)) {
            case Result.Success(NativeGitRepository repository) ->
                    new Result.Success<>(new PolicyBoundNativeGitRepository(
                            this,
                            repository));
            case Result.Failure<NativeGitRepository> failure -> failure;
        };
    }

    private RuntimeGitProxyBinding binding(String repositoryName) {
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
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Bootstrap repository name must not be blank");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().equals(".")) {
            throw new IllegalArgumentException("Bootstrap repository name is invalid");
        }
        return path.toString().replace('\\', '/');
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
