package pro.deta.orion.acl.storage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteGitAccessControlStorageTest {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "config/orion.xml";
    private static final String EXTRA_FILE = "config/roles.txt";

    @TempDir
    private Path tempDir;

    private final XmlService xmlService = new XmlService();

    @Test
    void loadsAclFromRemoteGitRepository() throws Exception {
        SeededRepository seededRepository = seedBareRepository("remote-user");
        RemoteGitAccessControlStorage storage = storage(
                seededRepository.repositoryPath(),
                tempDir.resolve("load-worktree"),
                List.of(ACL_FILE));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("Remote ACL should load");

        assertThat(snapshot.files()).containsOnlyKeys(ACL_FILE);
        assertThat(snapshot.version()).contains(seededRepository.commitId());
        assertThat(userIds(snapshot)).containsExactly("remote-user");
    }

    @Test
    void loadsAllConfiguredFilesFromRemoteGitRepository() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(ACL_FILE, aclBytes("multi-file-user"));
        files.put(EXTRA_FILE, "ROOT=CONNECT".getBytes(StandardCharsets.UTF_8));
        SeededRepository seededRepository = seedBareRepository("multi-file-acl.git", files);
        RemoteGitAccessControlStorage storage = storage(
                seededRepository.repositoryPath(),
                tempDir.resolve("multi-file-worktree"),
                List.of(ACL_FILE, EXTRA_FILE));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("Remote ACL files should load");

        assertThat(snapshot.files()).containsOnlyKeys(ACL_FILE, EXTRA_FILE);
        assertThat(snapshot.files().get(EXTRA_FILE)).containsExactly("ROOT=CONNECT".getBytes(StandardCharsets.UTF_8));
        assertThat(userIds(snapshot)).containsExactly("multi-file-user");
    }

    @Test
    void savesAclToRemoteGitRepository() throws Exception {
        Path repositoryPath = createBareRepository("saved-remote-acl.git");
        RemoteGitAccessControlStorage storage = storage(
                repositoryPath,
                tempDir.resolve("save-worktree"),
                List.of(ACL_FILE));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, aclBytes("saved-remote-user")),
                new AccessControlSaveRequest("save remote ACL", new UserEmail("tester", "tester@example.test")));

        RemoteGitAccessControlStorage freshStorage = storage(
                repositoryPath,
                tempDir.resolve("fresh-save-worktree"),
                List.of(ACL_FILE));
        AccessControlSnapshot snapshot = freshStorage.load().valueOrFailure("Saved remote ACL should load");
        assertThat(snapshot.version()).isPresent();
        assertThat(userIds(snapshot)).containsExactly("saved-remote-user");
    }

    @Test
    void overwritesAclInRemoteGitRepository() throws Exception {
        SeededRepository seededRepository = seedBareRepository("old-remote-user");
        RemoteGitAccessControlStorage storage = storage(
                seededRepository.repositoryPath(),
                tempDir.resolve("overwrite-worktree"),
                List.of(ACL_FILE));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, aclBytes("new-remote-user")),
                new AccessControlSaveRequest("overwrite remote ACL", new UserEmail("tester", "tester@example.test")));

        RemoteGitAccessControlStorage freshStorage = storage(
                seededRepository.repositoryPath(),
                tempDir.resolve("fresh-overwrite-worktree"),
                List.of(ACL_FILE));
        AccessControlSnapshot snapshot = freshStorage.load().valueOrFailure("Updated remote ACL should load");
        assertThat(snapshot.version()).isPresent();
        assertThat(snapshot.version().orElseThrow()).isNotEqualTo(seededRepository.commitId());
        assertThat(userIds(snapshot)).containsExactly("new-remote-user");
    }

    @Test
    void missingRemoteBranchReturnsNotFound() throws Exception {
        Path repositoryPath = createBareRepository("empty-remote-acl.git");
        RemoteGitAccessControlStorage storage = storage(
                repositoryPath,
                tempDir.resolve("missing-branch-worktree"),
                List.of(ACL_FILE));

        Result<AccessControlSnapshot> result = storage.load();

        assertThat(result).isInstanceOf(Result.Failure.class);
        Result.Failure<?> failure = (Result.Failure<?>) result;
        assertThat(failure.code()).isEqualTo(Result.FailureCode.NOT_FOUND);
    }

    @Test
    void missingConfiguredFileReturnsNotFound() throws Exception {
        Map<String, byte[]> files = Map.of(EXTRA_FILE, "ROOT=CONNECT".getBytes(StandardCharsets.UTF_8));
        SeededRepository seededRepository = seedBareRepository("missing-acl-file.git", files);
        RemoteGitAccessControlStorage storage = storage(
                seededRepository.repositoryPath(),
                tempDir.resolve("missing-file-worktree"),
                List.of(ACL_FILE));

        Result<AccessControlSnapshot> result = storage.load();

        assertThat(result).isInstanceOf(Result.Failure.class);
        Result.Failure<?> failure = (Result.Failure<?>) result;
        assertThat(failure.code()).isEqualTo(Result.FailureCode.NOT_FOUND);
    }

    @Test
    void rejectsConfiguredPathsEscapingRemoteWorktree() throws Exception {
        Path repositoryPath = createBareRepository("escaping-path-acl.git");
        RemoteGitAccessControlStorage storage = storage(
                repositoryPath,
                tempDir.resolve("escaping-path-worktree"),
                List.of("../orion.xml"));

        assertThatThrownBy(() -> storage.save(
                AccessControlSnapshot.singleFile("../orion.xml", aclBytes("escaping-user")),
                new AccessControlSaveRequest("save escaping ACL", new UserEmail("tester", "tester@example.test"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ACL file escapes remote ACL worktree: ../orion.xml");
    }

    @Test
    void normalizesSupportedRemoteGitSchemes() {
        assertThat(RemoteGitAccessControlStorage.supportsLocation("git+file:///tmp/acl.git")).isTrue();
        assertThat(RemoteGitAccessControlStorage.supportsLocation("git+ssh://git@example.test/acl.git")).isTrue();
        assertThat(RemoteGitAccessControlStorage.supportsLocation("git+https://example.test/acl.git")).isTrue();
        assertThat(RemoteGitAccessControlStorage.supportsLocation("ssh://git@example.test/acl.git")).isFalse();

        assertThat(RemoteGitAccessControlStorage.normalizeRemoteUri("git+file:///tmp/acl.git"))
                .isEqualTo("file:///tmp/acl.git");
        assertThat(RemoteGitAccessControlStorage.normalizeRemoteUri("git+ssh://git@example.test/acl.git"))
                .isEqualTo("ssh://git@example.test/acl.git");
        assertThat(RemoteGitAccessControlStorage.normalizeRemoteUri("git+https://example.test/acl.git"))
                .isEqualTo("https://example.test/acl.git");
    }

    @Test
    void rejectsInlinePasswordAuthSecret() {
        OrionConfiguration.BootstrapAccessControlConfig config = remoteConfig(
                Path.of("/tmp/acl.git"),
                List.of(ACL_FILE));
        config.getAuth().put("username", "git");
        config.getAuth().put("password", "plain-secret");

        assertThatThrownBy(() -> new RemoteGitAccessControlStorage(tempDir.resolve("auth-worktree"), config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("auth.password must use env: or file: reference");
    }

    @Test
    void resolverUsesRemoteStorageForGitPlusLocation() throws Exception {
        Path repositoryPath = createBareRepository("resolver-remote-acl.git");
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.resolve("resolver-base").toString());
        configuration.getBootstrap().setWorkDir("work");
        OrionConfiguration.BootstrapAccessControlConfig accessControl = configuration.getBootstrap().getAccessControl();
        accessControl.setLocation("git+" + repositoryPath.toUri());
        accessControl.setBranch(BRANCH);
        accessControl.setPaths(List.of(ACL_FILE));

        AccessControlStorage storage = new AccessControlStorageResolver(configuration, failingGitRepositoryProvider()).resolve();

        assertThat(storage).isInstanceOf(RemoteGitAccessControlStorage.class);
    }

    @Test
    void resolverKeepsPlainSshLocationUnsupported() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().getAccessControl().setLocation("ssh://git@example.test/acl.git");

        assertThatThrownBy(() -> new AccessControlStorageResolver(configuration, failingGitRepositoryProvider()).resolve())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported ACL location: ssh://git@example.test/acl.git");
    }

    private RemoteGitAccessControlStorage storage(Path repositoryPath, Path worktree, List<String> paths) {
        return new RemoteGitAccessControlStorage(worktree, remoteConfig(repositoryPath, paths));
    }

    private OrionConfiguration.BootstrapAccessControlConfig remoteConfig(Path repositoryPath, List<String> paths) {
        OrionConfiguration.BootstrapAccessControlConfig config = new OrionConfiguration.BootstrapAccessControlConfig();
        config.setLocation("git+" + repositoryPath.toUri());
        config.setBranch(BRANCH);
        config.setPaths(paths);
        return config;
    }

    private SeededRepository seedBareRepository(String userId) throws Exception {
        return seedBareRepository(userId + ".git", Map.of(ACL_FILE, aclBytes(userId)));
    }

    private SeededRepository seedBareRepository(String repositoryName, Map<String, byte[]> files) throws Exception {
        Path bareRepository = createBareRepository(repositoryName);
        Path seedWorktree = tempDir.resolve(repositoryName + "-seed");

        try (Git seed = Git.init()
                .setDirectory(seedWorktree.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                Path file = seedWorktree.resolve(entry.getKey());
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.write(file, entry.getValue());
                seed.add().addFilepattern(entry.getKey()).call();
            }
            ObjectId commitId = seed.commit()
                    .setAuthor("ACL Test", "acl@example.test")
                    .setCommitter("ACL Test", "acl@example.test")
                    .setMessage("seed ACL")
                    .call()
                    .toObjectId();
            seed.push()
                    .setRemote(bareRepository.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                    .call();
            return new SeededRepository(bareRepository, commitId.name());
        }
    }

    private Path createBareRepository(String repositoryName) throws Exception {
        Path repositoryPath = tempDir.resolve(repositoryName);
        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(repositoryPath.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            return repositoryPath;
        }
    }

    private byte[] aclBytes(String userId) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        xmlService.serialize(accessControlWithUser(userId), output);
        return output.toByteArray();
    }

    private AccessControl accessControlWithUser(String userId) {
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test"));
        return draft.toAccessControl();
    }

    private List<String> userIds(AccessControlSnapshot snapshot) throws Exception {
        return userIds(snapshot.files().get(ACL_FILE));
    }

    private List<String> userIds(byte[] content) throws Exception {
        AccessControl accessControl = xmlService.deserialize(new ByteArrayInputStream(content));
        List<String> userIds = new ArrayList<>();
        for (AccessControl.User user : accessControl.getUsers()) {
            userIds.add(user.getId());
        }
        return userIds;
    }

    private GitRepositoryProvider failingGitRepositoryProvider() {
        return new GitRepositoryProvider() {
            @Override
            public boolean exists(String repositoryName) {
                throw new AssertionError("Remote ACL storage must not use local repository provider");
            }

            @Override
            public Result<GitRepository> find(String repositoryName) {
                throw new AssertionError("Remote ACL storage must not use local repository provider");
            }

            @Override
            public Result<GitRepository> findOrCreate(String repositoryName) {
                throw new AssertionError("Remote ACL storage must not use local repository provider");
            }
        };
    }

    private record SeededRepository(Path repositoryPath, String commitId) {
    }
}
