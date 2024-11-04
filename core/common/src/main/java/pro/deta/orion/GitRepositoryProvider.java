package pro.deta.orion;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import pro.deta.orion.util.Result;


public interface GitRepositoryProvider {
    boolean exists(String repositoryName);

    Result<Repository> find(String repositoryName);

    Result<Repository> findOrCreate(String repositoryName);

    OrionGitRepositoryResolver createResolver();

    interface OrionGitRepositoryResolver<C> extends RepositoryResolver<C> {
    }
}
