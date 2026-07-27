package pro.deta.orion.git.client.repository;

public interface GitRepositoryContents {
    GitPackReader openPack(GitPackId packId) throws GitRepositoryAccessException;

    GitPackWriter beginPack() throws GitRepositoryAccessException;
}
