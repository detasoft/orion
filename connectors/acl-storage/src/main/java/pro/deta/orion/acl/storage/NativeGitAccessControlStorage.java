package pro.deta.orion.acl.storage;

import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class NativeGitAccessControlStorage implements AccessControlStorage {
    private final NativeGitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String configurationRef;
    private final List<String> paths;
    private final boolean createIfMissing;

    public NativeGitAccessControlStorage(
            BootstrapConfigurationSourceConfig config,
            NativeGitRepositoryProvider repositoryProvider) {
        Objects.requireNonNull(config, "config");
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        repositoryName = repositoryName(config.getLocation());
        configurationRef = refName(config.selectedRef());
        paths = config.selectedPaths();
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("At least one ACL path must be configured");
        }
        createIfMissing = config.isCreateDefaultIfMissing();
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        NativeGitRepository repository;
        try {
            repository = repositoryProvider.openForRead(repositoryName)
                    .valueOrFailure("Cannot open native repository " + repositoryName);
        } catch (RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
        try {
            if (!repository.refs().containsKey(configurationRef)) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            }
            GitRepositoryFileSnapshot snapshot = repository.loadFiles(configurationRef, paths);
            return new Result.Success<>(new AccessControlSnapshot(snapshot.files(), snapshot.version()));
        } catch (GitRepositoryFileNotFoundException error) {
            if (primaryPathIsMissing(repository)) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            }
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        } catch (GitOperationException | RuntimeException error) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, error.getMessage(), error);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        try {
            GitCommitAuthor author = author(request);
            if (snapshot.version().isPresent()) {
                NativeGitFileUpdate update = repositoryProvider.prepareFileUpdate(
                        repositoryName,
                        configurationRef,
                        snapshot.version().orElseThrow(),
                        snapshot.files(),
                        request.message(),
                        author);
                List<RefUpdateResult> results = repositoryProvider.publish(
                        repositoryName,
                        update.objects(),
                        update.refUpdates(),
                        true);
                if (results.contains(RefUpdateResult.STALE)) {
                    throw new AccessControlConcurrentUpdateException(
                            "ACL configuration changed concurrently",
                            null);
                }
            } else {
                repositoryProvider.saveFiles(
                        repositoryName,
                        configurationRef,
                        snapshot.files(),
                        request.message(),
                        author);
            }
        } catch (AccessControlConcurrentUpdateException error) {
            throw error;
        } catch (GitOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot save ACL to native repository " + repositoryName, error);
        }
    }

    private static GitCommitAuthor author(AccessControlSaveRequest request) {
        return new GitCommitAuthor(request.author().getUsername(), request.author().getEmail());
    }

    private boolean primaryPathIsMissing(NativeGitRepository repository) {
        if (paths.size() == 1) {
            return true;
        }
        try {
            repository.loadFiles(configurationRef, List.of(paths.getFirst()));
            return false;
        } catch (GitRepositoryFileNotFoundException missing) {
            return true;
        } catch (GitOperationException failure) {
            return false;
        }
    }

    @Override
    public String primaryPath() {
        return paths.getFirst();
    }

    @Override
    public boolean createIfMissing() {
        return createIfMissing;
    }

    @Override
    public ChangeSubscription onChange(Consumer<String> listener) {
        Consumer<String> registered = Objects.requireNonNull(listener, "listener");
        NativeGitRepository repository = repositoryProvider.openForRead(repositoryName)
                .valueOrFailure("Cannot open native repository " + repositoryName);
        NativeGitRepository.RefUpdateSubscription subscription = repository.onRefUpdate(update -> {
            if (configurationRef.equals(update.refName())) {
                registered.accept("native repository " + repository.name() + " ref " + configurationRef);
            }
        });
        return subscription::close;
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
