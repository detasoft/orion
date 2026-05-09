package pro.deta.orion.component;

import dagger.Lazy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.FileGitRepositoryProvider;
import pro.deta.orion.git.s3.S3GitRepositoryProvider;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

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
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "Storage location");
        return switch (resourceLocation.scheme()) {
            case ResourceScheme.Empty ignored -> fileGitRepositoryProvider.get();
            case ResourceScheme.File ignored -> fileGitRepositoryProvider.get();
            case ResourceScheme.Other other when "s3".equals(other.value()) -> s3GitRepositoryProvider.get();
            default -> throw new IllegalArgumentException("Unsupported repository storage location: " + location);
        };
    }
}
