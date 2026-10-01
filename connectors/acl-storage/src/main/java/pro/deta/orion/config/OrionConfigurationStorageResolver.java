package pro.deta.orion.config;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OrionConfigurationStorageResolver {
    private final BootstrapRepositorySources repositorySources;
    private final NativeGitRepositoryProvider repositoryProvider;

    public OrionConfigurationStorage resolve() {
        ResolvedBootstrapSource resolved = repositorySources.required(BootstrapRepositorySources.CONFIGURATION);
        if (resolved.repositoryName().isEmpty()) {
            throw new IllegalArgumentException("Orion configuration requires a resolved Git repository");
        }
        return new NativeGitOrionConfigurationStorage(resolved, repositoryProvider);
    }
}
