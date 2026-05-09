package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.storage.GitRepositoryProviderVersionedStorage;

import java.net.URI;
import java.nio.file.Path;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AccessControlStorageResolver {
    private final OrionConfiguration configuration;
    private final GitRepositoryProvider gitRepositoryProvider;

    public AccessControlStorage resolve() {
        return resolve(configuration.getBootstrap().getAccessControl());
    }

    AccessControlStorage resolve(OrionConfiguration.BootstrapAccessControlConfig accessControlConfig) {
        String location = accessControlConfig.getLocation();
        URI uri = URI.create(location);
        String scheme = uri.getScheme();
        if (scheme == null || "file".equalsIgnoreCase(scheme)) {
            return new LocalAccessControlStorage(accessControlConfig);
        }
        if ("local".equalsIgnoreCase(scheme)) {
            return new VersionedAccessControlStorage(
                    new GitRepositoryProviderVersionedStorage(
                            gitRepositoryProvider,
                            localRepositoryName(location),
                            accessControlConfig.getBranch()),
                    accessControlConfig.getPaths());
        }
        throw new IllegalArgumentException("Unsupported ACL location: " + location);
    }

    public static boolean isInternalLocalGitStorage(String location) {
        URI uri = URI.create(location);
        String scheme = uri.getScheme();
        return "local".equalsIgnoreCase(scheme);
    }

    public static String localRepositoryName(String location) {
        URI uri = URI.create(location);
        StringBuilder repositoryName = new StringBuilder();
        if (uri.getHost() != null && !uri.getHost().isBlank()) {
            repositoryName.append(uri.getHost());
        }
        if (uri.getPath() != null && !uri.getPath().isBlank()) {
            if (!repositoryName.isEmpty()) {
                repositoryName.append("/");
            }
            repositoryName.append(stripLeadingSlashes(uri.getPath()));
        }
        if (repositoryName.isEmpty() && uri.getSchemeSpecificPart() != null) {
            repositoryName.append(stripLeadingSlashes(uri.getSchemeSpecificPart()));
        }
        return Path.of(repositoryName.toString()).normalize().toString();
    }

    private static String stripLeadingSlashes(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
