package pro.deta.orion.git.jgit;

import org.eclipse.jgit.lib.Repository;
import pro.deta.orion.git.common.GitRepository;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

/**
 * Transitional Orion repository implementation backed by JGit. It keeps JGit behind the GitRepository interface while
 * protocol adapters are still implemented with JGit classes.
 */
public final class JGitRepository implements GitRepository {
    private final String name;
    private final Repository repository;

    public JGitRepository(String name, Repository repository) {
        this.name = Objects.requireNonNull(name, "name");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        File directory = repository.getDirectory();
        if (directory != null) {
            return directory.toString();
        }
        return repository.getIdentifier();
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> repositoryType) {
        Objects.requireNonNull(repositoryType, "repositoryType");
        if (repositoryType.isInstance(repository)) {
            return Optional.of(repositoryType.cast(repository));
        }
        if (repositoryType.isInstance(this)) {
            return Optional.of(repositoryType.cast(this));
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        repository.close();
    }
}
