package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.config.GitStorageDir;
import pro.deta.orion.git.util.GitUtils;
import pro.deta.orion.util.FileUtils;
import pro.deta.orion.util.Result;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static pro.deta.orion.util.Result.FailureCode.GENERAL;
import static pro.deta.orion.util.Result.FailureCode.NOT_FOUND;


@Singleton
@Slf4j
public class GitRepositoryProviderImpl implements GitRepositoryProvider {
    private final Path gitStorageDir;

    private final ConcurrentMap<String, Repository> repositoryCache = new ConcurrentHashMap<>();

    @Inject
    public GitRepositoryProviderImpl(@GitStorageDir Path gitStorageDir, OrionJGitSystemReader systemReader) {
        this.gitStorageDir = gitStorageDir.toAbsolutePath().normalize();
        FileUtils.mkdirs(this.gitStorageDir);
        log.warn("Git storage set to {}", this.gitStorageDir);
        GitUtils.gitConfigure(systemReader);
    }

    @Override
    public boolean exists(String repositoryName) {
        try {
            return resolveRepository(repositoryName).storagePath().toFile().exists();
        } catch (IllegalArgumentException e) {
            log.warn("Rejected invalid repository name {}", repositoryName);
            return false;
        }
    }

    @Override
    public Result<Repository> find(String repositoryName) {
        try {
            ResolvedRepository repository = resolveRepository(repositoryName);
            Repository r = repositoryCache.computeIfAbsent(repository.cacheKey(), (name) -> openRepository(repositoryName, repository.storagePath(), false));
            if (r == null) {
                return new Result.Failure<>(NOT_FOUND);
            }
            return new Result.Success<>(r);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected invalid repository name {}", repositoryName);
            return new Result.Failure<>(GENERAL, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error while looking for a repository {}", repositoryName, e);
            return new Result.Failure<>(GENERAL, e);
        }
    }

    @Override
    public Result<Repository> findOrCreate(String repositoryName) {
        try {
            ResolvedRepository repository = resolveRepository(repositoryName);
            Repository r = repositoryCache.computeIfAbsent(repository.cacheKey(), (name) -> openRepository(repositoryName, repository.storagePath(), true));
            if (r == null) {
                return new Result.Failure<>(NOT_FOUND);
            }
            return new Result.Success<>(r);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected invalid repository name {}", repositoryName);
            return new Result.Failure<>(GENERAL, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error while looking for a repository {}", repositoryName, e);
            return new Result.Failure<>(GENERAL, e);
        }
    }

    @Override
    public OrionGitRepositoryResolver createResolver() {
        return new OrionGitRepositoryResolver() {
            @Override
            public Repository open(Object req, String name) throws RepositoryNotFoundException, ServiceNotAuthorizedException, ServiceNotEnabledException, ServiceMayNotContinueException {
                return findOrCreate(name).valueOrFailure("Failed to open repository " + name);
            }
        };
    }

    private Repository openRepository(String repositoryName, Path repositoryStoragePath, boolean createIfNotExist) {
        try {
            File storagePathFile = repositoryStoragePath.toFile();
            if (!storagePathFile.exists()) {
                if (!createIfNotExist)
                    return null;
                storagePathFile.mkdirs();
            }
            Repository r = new FileRepositoryBuilder().setBare().setGitDir(storagePathFile).build();
            if (!r.getObjectDatabase().exists() || !((FileBasedConfig)r.getConfig()).getFile().exists()) {
                if (!createIfNotExist)
                    return null;
                initialRepositoryCreation(r);
            }
            return r;
        } catch (Exception e) {
            log.warn("Error while opening repository {}", repositoryName, e);
            return null;
        }
    }

    private ResolvedRepository resolveRepository(String repositoryName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("Repository name must not be empty");
        }

        Path relativePath = Path.of(repositoryName);
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("Repository name must be relative");
        }
        for (Path segment : relativePath) {
            String segmentName = segment.toString();
            if (segmentName.isBlank() || ".".equals(segmentName) || "..".equals(segmentName)) {
                throw new IllegalArgumentException("Repository name contains an unsafe path segment");
            }
        }

        Path normalizedRelativePath = relativePath.normalize();
        if (normalizedRelativePath.getNameCount() == 0) {
            throw new IllegalArgumentException("Repository name must not resolve to storage root");
        }
        Path storagePath = gitStorageDir.resolve(normalizedRelativePath).normalize();
        if (!storagePath.startsWith(gitStorageDir) || storagePath.equals(gitStorageDir)) {
            throw new IllegalArgumentException("Repository path escapes git storage root");
        }
        return new ResolvedRepository(normalizedRelativePath.toString(), storagePath);
    }

    private void initialRepositoryCreation(Repository r) {
        try {
            r.create(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private record ResolvedRepository(String cacheKey, Path storagePath) {
    }
}
