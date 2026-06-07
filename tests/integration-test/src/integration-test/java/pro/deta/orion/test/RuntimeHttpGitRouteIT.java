package pro.deta.orion.test;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeHttpGitRouteIT {
    private static final String BRANCH = "master";
    private static final String TEST_PASSWORD = "password";
    private static final String USERNAME = "http-git-user";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void jgitClientCanPushCloneAndFetchThroughHttpGitRoute() throws Exception {
        Path orionRoot = tempDir.resolve("orion-http-git");
        String repositoryName = "http-project";
        Path serverRepository = orionRoot.resolve("repos").resolve(repositoryName);
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String remoteUrl = orion.httpUrl("/r/" + repositoryName + ".git").toString();
            Path sourceDirectory = tempDir.resolve("http-source");
            Path cloneDirectory = tempDir.resolve("http-clone");

            try (Git source = initRepository(sourceDirectory)) {
                ObjectId initialCommit = createCommit(source, "README.md", "hello over http\n", "initial http commit");
                assertThatThrownBy(() -> source.push()
                        .setRemote(remoteUrl)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call())
                        .isInstanceOf(TransportException.class);
                assertThat(serverRepository).doesNotExist();

                String rootToken = TestBearerTokens.issueRootToken(
                        orion.accessControlService(),
                        orion.httpUrl("/api/admin/token"),
                        600);
                RuntimeHttpTestSupport.HttpResponse updateAcl = RuntimeHttpTestSupport.request(
                        "POST",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken),
                        "application/xml",
                        serialize(accessControlForHttpGitUser(repositoryName)));
                assertThat(updateAcl.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);

                String userToken = TestBearerTokens.issueToken(
                        orion.httpUrl("/api/admin/token"),
                        USERNAME,
                        TEST_PASSWORD.toCharArray(),
                        600);
                TransportConfigCallback authorization = bearerAuthorization(userToken);

                Iterable<PushResult> pushResults = source.push()
                        .setRemote(remoteUrl)
                        .setTransportConfigCallback(authorization)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call();

                assertThat(pushResults)
                        .flatExtracting(PushResult::getRemoteUpdates)
                        .extracting(RemoteRefUpdate::getStatus)
                        .containsExactly(RemoteRefUpdate.Status.OK);
                assertRepositoryContains(serverRepository, initialCommit, "README.md", "hello over http\n");

                try (Git clone = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(cloneDirectory.toFile())
                        .setBranch(BRANCH)
                        .setTransportConfigCallback(authorization)
                        .call()) {
                    assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("hello over http\n");

                    ObjectId updatedCommit = createCommit(source, "README.md", "updated over http\n", "update http commit");
                    source.push()
                            .setRemote(remoteUrl)
                            .setTransportConfigCallback(authorization)
                            .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                            .call();
                    assertRepositoryContains(serverRepository, updatedCommit, "README.md", "updated over http\n");

                    clone.fetch()
                            .setRemote("origin")
                            .setTransportConfigCallback(authorization)
                            .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH))
                            .call();
                    assertThat(clone.getRepository().resolve("refs/remotes/origin/" + BRANCH)).isEqualTo(updatedCommit);
                }
            }
        }

        assertThat(orionRoot.resolve("repos").resolve("r").resolve(repositoryName + ".git")).doesNotExist();
    }

    @Test
    void readOnlyBearerUserCanCloneButCannotPushOrCreateThroughHttpGitRoute() throws Exception {
        Path orionRoot = tempDir.resolve("orion-http-git-read-only");
        String repositoryName = "http-read-only-project";
        String createdRepositoryName = "http-read-only-created";
        Path serverRepository = orionRoot.resolve("repos").resolve(repositoryName);
        Path createdServerRepository = orionRoot.resolve("repos").resolve(createdRepositoryName);
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String remoteUrl = orion.httpUrl("/r/" + repositoryName + ".git").toString();
            Path sourceDirectory = tempDir.resolve("http-read-only-source");
            Path cloneDirectory = tempDir.resolve("http-read-only-clone");

            try (Git source = initRepository(sourceDirectory)) {
                ObjectId initialCommit = createCommit(source, "README.md", "seeded for read-only http\n", "seed http commit");
                String rootToken = TestBearerTokens.issueRootToken(
                        orion.accessControlService(),
                        orion.httpUrl("/api/admin/token"),
                        600);
                TransportConfigCallback rootAuthorization = bearerAuthorization(rootToken);

                Iterable<PushResult> seedPushResults = source.push()
                        .setRemote(remoteUrl)
                        .setTransportConfigCallback(rootAuthorization)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call();
                assertThat(seedPushResults)
                        .flatExtracting(PushResult::getRemoteUpdates)
                        .extracting(RemoteRefUpdate::getStatus)
                        .containsExactly(RemoteRefUpdate.Status.OK);
                assertRepositoryContains(serverRepository, initialCommit, "README.md", "seeded for read-only http\n");

                RuntimeHttpTestSupport.HttpResponse updateAcl = RuntimeHttpTestSupport.request(
                        "POST",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken),
                        "application/xml",
                        serialize(accessControlForHttpGitUser(repositoryName, true, false, false)));
                assertThat(updateAcl.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);

                String userToken = TestBearerTokens.issueToken(
                        orion.httpUrl("/api/admin/token"),
                        USERNAME,
                        TEST_PASSWORD.toCharArray(),
                        600);
                TransportConfigCallback readOnlyAuthorization = bearerAuthorization(userToken);

                assertThatThrownBy(() -> source.push()
                        .setRemote(orion.httpUrl("/r/" + createdRepositoryName + ".git").toString())
                        .setTransportConfigCallback(readOnlyAuthorization)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call())
                        .isInstanceOf(TransportException.class);
                assertThat(createdServerRepository).doesNotExist();

                try (Git clone = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(cloneDirectory.toFile())
                        .setBranch(BRANCH)
                        .setTransportConfigCallback(readOnlyAuthorization)
                        .call()) {
                    assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("seeded for read-only http\n");

                    createCommit(clone, "README.md", "read-only update over http\n", "read-only update http commit");
                    assertThatThrownBy(() -> clone.push()
                            .setRemote("origin")
                            .setTransportConfigCallback(readOnlyAuthorization)
                            .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                            .call())
                            .isInstanceOf(TransportException.class);
                }

                assertRepositoryContains(serverRepository, initialCommit, "README.md", "seeded for read-only http\n");
            }
        }
    }

    @Test
    void bearerUserIsLimitedToGrantedHttpGitBranchAndCannotForcePushWithoutForceGrant() throws Exception {
        Path orionRoot = tempDir.resolve("orion-http-git-branch");
        String repositoryName = "http-branch-project";
        String featureBranch = "feature";
        Path serverRepository = orionRoot.resolve("repos").resolve(repositoryName);
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String remoteUrl = orion.httpUrl("/r/" + repositoryName + ".git").toString();
            Path sourceDirectory = tempDir.resolve("http-branch-source");
            Path cloneDirectory = tempDir.resolve("http-branch-clone");
            Path forceDirectory = tempDir.resolve("http-branch-force-source");

            try (Git source = initRepository(sourceDirectory)) {
                ObjectId masterCommit = createCommit(source, "README.md", "master over http\n", "seed master");
                String rootToken = TestBearerTokens.issueRootToken(
                        orion.accessControlService(),
                        orion.httpUrl("/api/admin/token"),
                        600);
                TransportConfigCallback rootAuthorization = bearerAuthorization(rootToken);

                assertPushStatus(
                        source.push()
                                .setRemote(remoteUrl)
                                .setTransportConfigCallback(rootAuthorization)
                                .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                                .call(),
                        RemoteRefUpdate.Status.OK);

                source.checkout()
                        .setCreateBranch(true)
                        .setName(featureBranch)
                        .call();
                ObjectId featureCommit = createCommit(source, "FEATURE.md", "feature over http\n", "seed feature");
                assertPushStatus(
                        source.push()
                                .setRemote(remoteUrl)
                                .setTransportConfigCallback(rootAuthorization)
                                .setRefSpecs(new RefSpec("refs/heads/" + featureBranch + ":refs/heads/" + featureBranch))
                                .call(),
                        RemoteRefUpdate.Status.OK);
                source.checkout().setName(BRANCH).call();

                assertRepositoryRef(serverRepository, BRANCH, masterCommit);
                assertRepositoryRef(serverRepository, featureBranch, featureCommit);

                RuntimeHttpTestSupport.HttpResponse updateAcl = RuntimeHttpTestSupport.request(
                        "POST",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken),
                        "application/xml",
                        serialize(accessControlForHttpGitUser(repositoryName, true, true, true, BRANCH, false)));
                assertThat(updateAcl.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);

                String userToken = TestBearerTokens.issueToken(
                        orion.httpUrl("/api/admin/token"),
                        USERNAME,
                        TEST_PASSWORD.toCharArray(),
                        600);
                TransportConfigCallback branchAuthorization = bearerAuthorization(userToken);

                try (Git clone = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(cloneDirectory.toFile())
                        .setBranchesToClone(List.of("refs/heads/" + BRANCH))
                        .setBranch(BRANCH)
                        .setTransportConfigCallback(branchAuthorization)
                        .call()) {
                    assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("master over http\n");

                    assertThatThrownBy(() -> clone.fetch()
                            .setRemote("origin")
                            .setTransportConfigCallback(branchAuthorization)
                            .setRefSpecs(new RefSpec("refs/heads/" + featureBranch + ":refs/remotes/origin/" + featureBranch))
                            .call())
                            .isInstanceOf(TransportException.class);
                    assertThat(clone.getRepository().resolve("refs/remotes/origin/" + featureBranch)).isNull();
                }

                source.checkout().setName(featureBranch).call();
                createCommit(source, "FEATURE.md", "denied feature update over http\n", "denied feature update");
                assertPushStatus(
                        source.push()
                                .setRemote(remoteUrl)
                                .setTransportConfigCallback(branchAuthorization)
                                .setRefSpecs(new RefSpec("refs/heads/" + featureBranch + ":refs/heads/" + featureBranch))
                                .call(),
                        RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
                assertRepositoryRef(serverRepository, featureBranch, featureCommit);

                try (Git forceSource = initRepository(forceDirectory)) {
                    createCommit(forceSource, "README.md", "denied force over http\n", "denied force");
                    assertPushStatus(
                            forceSource.push()
                                    .setRemote(remoteUrl)
                                    .setTransportConfigCallback(branchAuthorization)
                                    .setRefSpecs(new RefSpec("+refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                                    .call(),
                            RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
                }
                assertRepositoryRef(serverRepository, BRANCH, masterCommit);
            }
        }
    }

    private static AccessControl accessControlForHttpGitUser(String repositoryName) {
        return accessControlForHttpGitUser(repositoryName, true, true, true);
    }

    private static AccessControl accessControlForHttpGitUser(
            String repositoryName,
            boolean read,
            boolean write,
            boolean create) {
        return accessControlForHttpGitUser(repositoryName, read, write, create, "*", false);
    }

    private static AccessControl accessControlForHttpGitUser(
            String repositoryName,
            boolean read,
            boolean write,
            boolean create,
            String branch,
            boolean force) {
        AccessControlDraft draft = ACLUtil.generateDefaultAccessControl(
                TEST_PASSWORD_HASH,
                AccessControl.CredentialType.SHA1).toDraft();
        AccessControlDraft.User user = ACLUtil.createUser(USERNAME, USERNAME + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH);
        AccessControlDraft.Grant grant = user.addGrant("REPOSITORY_" + repositoryName)
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .addKey(AccessControl.GrantKey.BRANCH, branch);
        if (read) {
            grant.addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING);
        }
        if (write) {
            grant.addKey(AccessControl.GrantKey.WRITE, AccessControl.TRUE_STRING);
        }
        if (create) {
            grant.addKey(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING);
        }
        if (force) {
            grant.addKey(AccessControl.GrantKey.FORCE, AccessControl.TRUE_STRING);
        }
        draft.getUsers().add(user);
        return draft.toAccessControl();
    }

    private static byte[] serialize(AccessControl accessControl) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new XmlService().serialize(accessControl, output);
            return output.toByteArray();
        }
    }

    private static TransportConfigCallback bearerAuthorization(String token) {
        return transport -> {
            if (transport instanceof TransportHttp http) {
                http.setAdditionalHeaders(Map.of("Authorization", TestBearerTokens.bearer(token)));
            }
        };
    }

    private static Git initRepository(Path directory) throws Exception {
        Files.createDirectories(directory);
        Git git = Git.init()
                .setDirectory(directory.toFile())
                .setInitialBranch(BRANCH)
                .call();
        git.getRepository().getConfig().setString("user", null, "name", "HTTP Git Test");
        git.getRepository().getConfig().setString("user", null, "email", "http-git@example.test");
        git.getRepository().getConfig().save();
        return git;
    }

    private static ObjectId createCommit(Git git, String fileName, String content, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(fileName), content);
        git.add().addFilepattern(fileName).call();
        return git.commit()
                .setAuthor("HTTP Git Test", "http-git@example.test")
                .setCommitter("HTTP Git Test", "http-git@example.test")
                .setMessage(message + " " + Instant.now())
                .call()
                .toObjectId();
    }

    private static void assertRepositoryContains(Path repositoryPath, ObjectId commitId, String fileName, String expectedContent)
            throws Exception {
        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile())) {
            assertThat(repository.exactRef("refs/heads/" + BRANCH).getObjectId()).isEqualTo(commitId);
            assertThat(repository.getObjectDatabase().has(commitId)).isTrue();
            assertThat(readFileFromCommit(repository, commitId, fileName)).isEqualTo(expectedContent);
        }
    }

    private static void assertRepositoryRef(Path repositoryPath, String branch, ObjectId commitId) throws Exception {
        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile())) {
            var ref = repository.exactRef("refs/heads/" + branch);
            assertThat(ref).isNotNull();
            assertThat(ref.getObjectId()).isEqualTo(commitId);
        }
    }

    private static void assertPushStatus(Iterable<PushResult> pushResults, RemoteRefUpdate.Status status) {
        assertThat(pushResults)
                .flatExtracting(PushResult::getRemoteUpdates)
                .extracting(RemoteRefUpdate::getStatus)
                .containsExactly(status);
    }

    private static String readFileFromCommit(Repository repository, ObjectId commitId, String fileName) throws Exception {
        try (RevWalk revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(commitId);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, fileName, commit.getTree())) {
                assertThat(treeWalk).isNotNull();
                try (var reader = repository.newObjectReader()) {
                    return new String(reader.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
                }
            }
        }
    }
}
