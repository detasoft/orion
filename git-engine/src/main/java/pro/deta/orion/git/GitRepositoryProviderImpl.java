package pro.deta.orion.git;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import pro.deta.orion.util.OrionPathResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

@AllArgsConstructor
@Slf4j
public class GitRepositoryProviderImpl implements GitRepositoryProvider {
    private final Path pathToStorageDir;
    private final Cache<String, Repository> repositoryCache = CacheBuilder.newBuilder().maximumSize(3).build();

    public GitRepositoryProviderImpl(OrionPathResolver orionPathResolver) {
        this.pathToStorageDir = orionPathResolver.resolve(orionPathResolver.getConfiguration().getGit().getStoragePath());
        if (!pathToStorageDir.toFile().exists())
            pathToStorageDir.toFile().mkdirs();
        log.debug("Git storage set to {}", pathToStorageDir);
    }

    @Override
    public boolean exists(String repositoryName) {
        return pathToStorageDir.resolve(repositoryName).toFile().exists();
    }

    @Override
    public Repository find(String repositoryName) {
        Repository r;
        try {
            r = repositoryCache.get(repositoryName, () -> openRepository(repositoryName));
        } catch (ExecutionException e) {
            log.error("Error while looking for a repository {}", repositoryName, e);
            throw new RuntimeException(e);
        }
        return r;
    }

    @Override
    public Repository findOrCreate(String repositoryName) throws IOException {
        Repository r = find(repositoryName);
        try {
            if (!((FileBasedConfig)r.getConfig()).getFile().exists()) {
                r.create(true);
            }
        } catch (NoWorkTreeException e) {
            log.error("Error while opening repository {}", repositoryName, e);
        }
        return r;
    }

    private Repository openRepository(String repositoryName) throws IOException {
        Path baseDir = pathToStorageDir.resolve(repositoryName);
        Repository r = new FileRepositoryBuilder().setGitDir(baseDir.toFile()).build();
        return r;
    }
}
