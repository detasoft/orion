package pro.deta.orion.acl.storage;

import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class NativeGitAccessControlStorage implements AccessControlStorage {
    private final NativeGitRepository repository;
    private final String configurationRef;
    private final List<String> paths;

    public NativeGitAccessControlStorage(
            OrionConfiguration.BootstrapAccessControlConfig config,
            NativeGitRepositoryProvider repositoryProvider) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        repository = findOrCreate(repositoryProvider, repositoryName(config.getLocation()));
        configurationRef = refName(config.configurationRef());
        paths = List.copyOf(Objects.requireNonNull(config.getPaths(), "paths"));
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("At least one ACL path must be configured");
        }
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        if (!repository.refs().containsKey(configurationRef)) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }
        try {
            GitRepositoryFileSnapshot snapshot = repository.loadFiles(configurationRef, paths);
            return new Result.Success<>(new AccessControlSnapshot(snapshot.files(), snapshot.version()));
        } catch (GitOperationException | IllegalArgumentException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        try {
            repository.saveFiles(
                    configurationRef,
                    snapshot.files(),
                    request.message(),
                    new GitCommitAuthor(request.author().getUsername(), request.author().getEmail()));
        } catch (GitOperationException error) {
            throw new IllegalStateException("Cannot save ACL to native repository " + repository.name(), error);
        }
    }

    @Override
    public String primaryPath() {
        return paths.getFirst();
    }

    @Override
    public ChangeSubscription onChange(Consumer<String> listener) {
        Consumer<String> registered = Objects.requireNonNull(listener, "listener");
        NativeGitRepository.RefUpdateSubscription subscription = repository.onRefUpdate(update -> {
            if (configurationRef.equals(update.refName())) {
                registered.accept("native repository " + repository.name() + " ref " + configurationRef);
            }
        });
        return subscription::close;
    }

    private static NativeGitRepository findOrCreate(
            NativeGitRepositoryProvider provider,
            String repositoryName) {
        if (provider.exists(repositoryName)) {
            return provider.find(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
        }
        Result<NativeGitRepository> created = provider.create(repositoryName);
        if (created instanceof Result.Failure<NativeGitRepository> failure
                && failure.code() == Result.FailureCode.FILE_ALREADY_EXISTS) {
            return provider.find(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
        }
        return created.valueOrFailure("Cannot create native repository " + repositoryName);
    }

    private static String repositoryName(String location) {
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "ACL location");
        if (!(resourceLocation.scheme() instanceof ResourceScheme.Local)) {
            throw new IllegalArgumentException("Native ACL location must use local: " + location);
        }
        String name = resourceLocation.normalizedRelativePath();
        Path path = Path.of(name);
        if (name.isBlank()
                || path.isAbsolute()
                || name.equals(".")
                || name.equals("..")
                || name.startsWith("../")) {
            throw new IllegalArgumentException("Invalid native configuration repository name: " + name);
        }
        return name.replace('\\', '/');
    }

    private static String refName(String configuredRef) {
        if (configuredRef == null || configuredRef.isBlank()) {
            throw new IllegalArgumentException("Configuration ref must not be empty");
        }
        return configuredRef.startsWith("refs/")
                ? configuredRef
                : "refs/heads/" + configuredRef;
    }
}
