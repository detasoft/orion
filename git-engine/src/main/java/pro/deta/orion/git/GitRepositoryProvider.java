package pro.deta.orion.git;

import org.eclipse.jgit.lib.Repository;

import java.io.IOException;

public interface GitRepositoryProvider {
    boolean exists(String repositoryName);

    Repository find(String repositoryName);

    Repository findOrCreate(String repositoryName) throws IOException;
}
