package pro.deta.orion.acl.storage;

import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.storage.LocalGitVersionedStorage;
import pro.deta.orion.git.storage.VersionedStorage;

import java.net.URI;
import java.nio.file.Path;

public class LocalGitAccessControlStorage extends VersionedAccessControlStorage {
    public LocalGitAccessControlStorage(OrionConfiguration.AccessControlConfig config) {
        this(new LocalGitVersionedStorage(repositoryPathFrom(config), config.getBranch()), config.getSettingsFileName());
    }

    public LocalGitAccessControlStorage(Path repositoryPath, String branch, String primaryPath) {
        this(new LocalGitVersionedStorage(repositoryPath, branch), primaryPath);
    }

    public LocalGitAccessControlStorage(VersionedStorage versionedStorage, String primaryPath) {
        super(versionedStorage, primaryPath);
    }

    private static Path repositoryPathFrom(OrionConfiguration.AccessControlConfig config) {
        URI uri = URI.create(config.getUrl());
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return Path.of(uri);
        }
        return Path.of(config.getUrl());
    }
}
