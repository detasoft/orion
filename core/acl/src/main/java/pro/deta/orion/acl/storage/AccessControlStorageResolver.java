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
    private final JDBCAccessControlStorage jdbcAccessControlStorage;
    private final LocalAccessControlStorage localAccessControlStorage;

    public AccessControlStorage resolve() {
        return resolve(configuration.getAccessControl().getType(), configuration.getAccessControl());
    }

    AccessControlStorage resolve(OrionConfiguration.ACLStorageType type, OrionConfiguration.AccessControlConfig accessControlConfig) {
        return switch (type) {
            case GIT -> gitStorage(accessControlConfig);
            case JDBC -> {
                jdbcAccessControlStorage.setEnabled(true);
                yield jdbcAccessControlStorage;
            }
            case LOCAL -> {
                localAccessControlStorage.setEnabled(true);
                yield localAccessControlStorage;
            }
        };
    }

    private AccessControlStorage gitStorage(OrionConfiguration.AccessControlConfig accessControlConfig) {
        if (isIndependentLocalGitStorage(accessControlConfig.getUrl())) {
            return new LocalGitAccessControlStorage(accessControlConfig);
        }
        if (isInternalLocalGitStorage(accessControlConfig.getUrl())) {
            return new VersionedAccessControlStorage(
                    new GitRepositoryProviderVersionedStorage(
                            gitRepositoryProvider,
                            localRepositoryName(accessControlConfig.getUrl()),
                            accessControlConfig.getBranch()),
                    accessControlConfig.getSettingsFileName());
        }
        throw new IllegalArgumentException("Unsupported Git ACL location: " + accessControlConfig.getUrl());
    }

    public static boolean isIndependentLocalGitStorage(String location) {
        String scheme = URI.create(location).getScheme();
        return scheme == null || "file".equalsIgnoreCase(scheme);
    }

    public static boolean isInternalLocalGitStorage(String location) {
        URI uri = URI.create(location);
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        return switch (scheme) {
            case "local" -> true;
            default -> false;
        };
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
