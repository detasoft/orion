package pro.deta.orion.git.client.repository;

/**
 * Backend-neutral pack-content storage. It does not list or update refs:
 * orchestration must complete content storage before publishing a commit
 * through {@link GitRepositoryRefs}.
 */
public interface GitRepositoryContents {
    GitPackReader openPack(GitPackId packId) throws GitRepositoryAccessException;

    GitPackWriter beginPack() throws GitRepositoryAccessException;
}
