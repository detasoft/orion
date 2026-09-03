package pro.deta.orion.git.workflow.orion;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitWireBootstrap;
import pro.deta.orion.git.workflow.GitCapability;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.RepositorySnapshot;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class OrionGitServer implements GitServer {
    private static final String MAIN_REF = "refs/heads/main";

    private final Map<String, NativeGitRepository> repositories = new LinkedHashMap<>();
    private Path root;
    private Path storageRoot;
    private NativeGitRepositoryProvider provider;
    private GitNativeTransportService service;
    private int boundPort;
    private boolean closed;

    @Override
    public String name() {
        return "orion";
    }

    @Override
    public Set<GitCapability> capabilities() {
        return OrionGitEngines.SERVER_CAPABILITIES;
    }

    @Override
    public synchronized GitRemoteRepository createRemoteRepository(Path directory, String repositoryName) {
        requireOpen("provision a repository");
        requireRepositoryName(repositoryName);
        Path requestedRoot = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (root != null && !root.equals(requestedRoot)) {
            throw new IllegalArgumentException("Orion repositories must share one isolated root");
        }
        if (root == null) {
            start(requestedRoot);
        }
        NativeGitRepository repository = success(
                provider.create(GitWireBootstrap.normalizeRepositoryPath(repositoryName)),
                "provision " + repositoryName);
        repositories.put(repositoryName, repository);
        return remoteRepository(repositoryName);
    }

    @Override
    public synchronized GitRemoteRepository missingRemoteRepository(Path directory, String repositoryName) {
        requireOpen("declare a missing repository");
        requireRepositoryName(repositoryName);
        Path requestedRoot = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (root != null && !root.equals(requestedRoot)) {
            throw new IllegalArgumentException("Orion repositories must share one isolated root");
        }
        if (root == null) {
            start(requestedRoot);
        }
        return remoteRepository(repositoryName);
    }

    @Override
    public synchronized RepositorySnapshot snapshot(GitRemoteRepository remote) throws Exception {
        requireOpen("capture a snapshot");
        NativeGitRepository repository = repository(remote);
        if (repository.refs().isEmpty()) {
            return RepositorySnapshot.of(MAIN_REF, Map.of(), Map.of());
        }
        Path observer = Files.createTempDirectory(root, ".orion-jgit-observer-");
        Throwable failure = null;
        try (Git observation = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(observer.toFile())
                .call()) {
            observation.getRepository().getConfig().setInt("gc", null, "auto", 0);
            observation.getRepository().getConfig().save();
            observation.fetch()
                    .setRemote(remote.uri())
                    .setRefSpecs(
                            new RefSpec("+refs/heads/*:refs/heads/*"),
                            new RefSpec("+refs/tags/*:refs/tags/*"))
                    .call();
            return RepositorySnapshot.capture(observation.getRepository());
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                deleteRecursively(observer);
            } catch (IOException cleanupError) {
                if (failure != null) {
                    failure.addSuppressed(cleanupError);
                } else {
                    throw cleanupError;
                }
            }
        }
    }

    @Override
    public synchronized String diagnostics() {
        if (service == null) {
            return "Orion native Git server; running=false; closed=" + closed
                    + "; storage=uninitialized";
        }
        return "Orion native Git server; endpoint=127.0.0.1:" + boundPort
                + "; running=" + service.isRunning()
                + "; closed=" + closed
                + "; storage=" + storageRoot;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (service != null) {
            service.onStop();
        }
        for (NativeGitRepository repository : repositories.values()) {
            repository.close();
        }
        repositories.clear();
    }

    private void requireOpen(String operation) {
        if (closed) {
            throw new IllegalStateException("Cannot " + operation + ": Orion Git server is closed");
        }
    }

    private void start(Path requestedRoot) {
        root = requestedRoot;
        storageRoot = root.resolve(".orion-native-storage");
        provider = new FileNativeGitRepositoryProvider(storageRoot);
        GitTransportConfig config = new GitTransportConfig("127.0.0.1", 0);
        config.setEnabled(true);
        service = new GitNativeTransportService(
                config,
                new DefaultGitNativeRepositoryService(provider));
        service.onStart();
        if (!service.isRunning() || service.boundPort() <= 0) {
            throw new IllegalStateException("Orion native Git server failed to start on loopback port zero");
        }
        boundPort = service.boundPort();
    }

    private NativeGitRepository repository(GitRemoteRepository remote) {
        String path = java.net.URI.create(remote.uri()).getPath();
        String name = path == null || !path.startsWith("/") ? "" : path.substring(1);
        NativeGitRepository repository = repositories.get(name);
        if (repository == null) {
            repository = success(
                    provider.find(GitWireBootstrap.normalizeRepositoryPath(name)),
                    "find " + name);
            repositories.put(name, repository);
        }
        return repository;
    }

    private GitRemoteRepository remoteRepository(String repositoryName) {
        return new GitRemoteRepository(
                storageRoot,
                "git://127.0.0.1:" + boundPort + "/" + repositoryName);
    }

    private static NativeGitRepository success(Result<NativeGitRepository> result, String operation) {
        if (result instanceof Result.Success<NativeGitRepository> success) {
            return success.value();
        }
        Result.Failure<NativeGitRepository> failure = (Result.Failure<NativeGitRepository>) result;
        throw new IllegalStateException(
                "Cannot " + operation + ": " + failure.message(),
                failure.throwable());
    }

    private static void requireRepositoryName(String repositoryName) {
        Objects.requireNonNull(repositoryName, "repositoryName");
        if (repositoryName.isBlank()
                || ".".equals(repositoryName)
                || "..".equals(repositoryName)
                || repositoryName.contains("/")
                || repositoryName.contains("\\")) {
            throw new IllegalArgumentException("Repository name must be one path segment: " + repositoryName);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        deleteRecursively(directory, Files::deleteIfExists);
    }

    @TestOnly
    static void deleteRecursively(Path directory, PathDeletion deletion) throws IOException {
        IOException[] failure = new IOException[1];
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    delete(file, deletion, failure);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    record(error, failure);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException error) {
                    if (error != null) {
                        record(error, failure);
                    }
                    delete(path, deletion, failure);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) {
            // Temporary JGit metadata may disappear while its observer directory is being cleaned.
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private static void delete(Path path, PathDeletion deletion, IOException[] failure) {
        try {
            deletion.delete(path);
        } catch (IOException error) {
            record(error, failure);
        }
    }

    private static void record(IOException error, IOException[] failure) {
        if (error instanceof NoSuchFileException) {
            return;
        }
        if (failure[0] == null) {
            failure[0] = error;
        } else if (failure[0] != error) {
            failure[0].addSuppressed(error);
        }
    }

    @FunctionalInterface
    interface PathDeletion {
        void delete(Path path) throws IOException;
    }
}
