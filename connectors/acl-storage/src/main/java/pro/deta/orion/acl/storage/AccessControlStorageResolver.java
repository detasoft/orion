package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AccessControlStorageResolver {
    private final BootstrapRepositorySources repositorySources;
    private final NativeGitRepositoryProvider repositoryProvider;

    public AccessControlStorage resolve() {
        ResolvedBootstrapSource resolved = repositorySources.required(BootstrapRepositorySources.CONFIGURATION);
        if (resolved.repositoryName().isEmpty()) {
            throw new IllegalArgumentException("ACL configuration requires a resolved Git repository");
        }
        return new NativeGitAccessControlStorage(resolved, repositoryProvider);
    }
}
