package pro.deta.orion.acl.storage;

import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.storage.LocalGitVersionedStorage;
import pro.deta.orion.git.storage.VersionedStorage;

import java.net.URI;
import java.nio.file.Path;

public class LocalGitAccessControlStorage extends VersionedAccessControlStorage {
    public LocalGitAccessControlStorage(OrionConfiguration.BootstrapAccessControlConfig config) {
        this(new LocalGitVersionedStorage(repositoryPathFrom(config), config.getBranch()), config.getPaths());
    }

    public LocalGitAccessControlStorage(Path repositoryPath, String branch, String primaryPath) {
        this(new LocalGitVersionedStorage(repositoryPath, branch), primaryPath);
    }

    public LocalGitAccessControlStorage(VersionedStorage versionedStorage, String primaryPath) {
        super(versionedStorage, primaryPath);
    }

    public LocalGitAccessControlStorage(VersionedStorage versionedStorage, java.util.List<String> paths) {
        super(versionedStorage, paths);
    }

    private static Path repositoryPathFrom(OrionConfiguration.BootstrapAccessControlConfig config) {
        URI uri = URI.create(config.getLocation());
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return Path.of(uri);
        }
        return Path.of(config.getLocation());
    }
}
