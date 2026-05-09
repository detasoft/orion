package pro.deta.orion.acl.storage;

import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.storage.LocalGitVersionedStorage;
import pro.deta.orion.git.storage.VersionedStorage;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

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
        ResourceLocation location = ResourceLocation.parse(config.getLocation(), "ACL location");
        if (location.hasScheme(ResourceScheme.FILE)) {
            return Path.of(location.pathOrSchemeSpecificPart("File ACL location must include a path"));
        }
        return Path.of(config.getLocation());
    }
}
