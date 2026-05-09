package pro.deta.orion.component;

import dagger.Lazy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.FileGitRepositoryProvider;
import pro.deta.orion.git.s3.S3GitRepositoryProvider;

import java.net.URI;

@Singleton
public class GitRepositoryProviderResolver {
    private final OrionConfiguration configuration;
    private final Lazy<FileGitRepositoryProvider> fileGitRepositoryProvider;
    private final Lazy<S3GitRepositoryProvider> s3GitRepositoryProvider;

    @Inject
    public GitRepositoryProviderResolver(
            OrionConfiguration configuration,
            Lazy<FileGitRepositoryProvider> fileGitRepositoryProvider,
            Lazy<S3GitRepositoryProvider> s3GitRepositoryProvider) {
        this.configuration = configuration;
        this.fileGitRepositoryProvider = fileGitRepositoryProvider;
        this.s3GitRepositoryProvider = s3GitRepositoryProvider;
    }

    public GitRepositoryProvider resolve() {
        return resolve(configuration.getStorage().getLocation());
    }

    GitRepositoryProvider resolve(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Storage location must not be empty");
        }
        URI uri = URI.create(location);
        String scheme = uri.getScheme();
        if (scheme == null || "file".equalsIgnoreCase(scheme)) {
            return fileGitRepositoryProvider.get();
        }
        if ("s3".equalsIgnoreCase(scheme)) {
            return s3GitRepositoryProvider.get();
        }
        throw new IllegalArgumentException("Unsupported repository storage location: " + location);
    }
}
