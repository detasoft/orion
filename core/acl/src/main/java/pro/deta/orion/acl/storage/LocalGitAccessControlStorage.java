package pro.deta.orion.acl.storage;

import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.storage.GitFileSnapshot;
import pro.deta.orion.git.storage.LocalGitFileStorage;
import pro.deta.orion.util.Result;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public class LocalGitAccessControlStorage implements AccessControlStorage {
    private final LocalGitFileStorage gitFileStorage;
    private final String branch;
    private final String primaryPath;

    public LocalGitAccessControlStorage(OrionConfiguration.AccessControlConfig config) {
        this(new LocalGitFileStorage(repositoryPathFrom(config)), config.getBranch(), config.getSettingsFileName());
    }

    public LocalGitAccessControlStorage(Path repositoryPath, String branch, String primaryPath) {
        this(new LocalGitFileStorage(repositoryPath), branch, primaryPath);
    }

    LocalGitAccessControlStorage(LocalGitFileStorage gitFileStorage, String branch, String primaryPath) {
        this.gitFileStorage = Objects.requireNonNull(gitFileStorage, "gitFileStorage");
        this.branch = Objects.requireNonNull(branch, "branch");
        this.primaryPath = Objects.requireNonNull(primaryPath, "primaryPath");
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        return switch (gitFileStorage.load(branch, primaryPath)) {
            case Result.Success<GitFileSnapshot>(var snapshot) -> new Result.Success<>(
                    new AccessControlSnapshot(snapshot.files(), snapshot.version()));
            case Result.Failure<GitFileSnapshot> failure -> new Result.Failure<>(failure);
        };
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        gitFileStorage.save(branch, snapshot.files(), request.message(), request.author());
    }

    @Override
    public String primaryPath() {
        return primaryPath;
    }

    private static Path repositoryPathFrom(OrionConfiguration.AccessControlConfig config) {
        URI uri = URI.create(config.getUrl());
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return Path.of(uri);
        }
        return Path.of(config.getUrl());
    }
}
