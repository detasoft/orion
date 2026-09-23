package pro.deta.orion.git.sync;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitTcpClientTransport;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitServers;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmartHttpGitRemoteGatewayTest {
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void fetchesAnEmptyRemoteWithoutRequestingAPack(
            @TempDir Path temporaryDirectory) throws Exception {
        try (GitServer server = GitServers.jgit()) {
            GitRemoteRepository remote = server.createRemoteRepository(
                    temporaryDirectory,
                    "empty.git");
            NativeGitRepository local = nativeRepository(
                    temporaryDirectory.resolve("orion"));
            try (SmartHttpGitRemoteGateway gateway = gateway(remote)) {
                assertThat(gateway.fetchHeads(local).heads()).isEmpty();
                assertThat(local.refs()).isEmpty();
            }
        }
    }

    @Test
    void fetchesEveryHeadIntoTrackingRefsAndPushesWithExpectedId(
            @TempDir Path temporaryDirectory) throws Exception {
        try (GitServer server = GitServers.jgit()) {
            GitRemoteRepository remote = server.createRemoteRepository(
                    temporaryDirectory,
                    "upstream.git");
            Seed seed = seedRemote(temporaryDirectory, remote);
            NativeGitRepository local = nativeRepository(
                    temporaryDirectory.resolve("orion"));
            try (SmartHttpGitRemoteGateway gateway = gateway(remote)) {
                GitHeads fetched = gateway.fetchHeads(local);

                assertThat(gateway.listHeads()).isEqualTo(fetched);
                assertThat(fetched.heads()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "refs/heads/main", seed.main(),
                        "refs/heads/release", seed.release()));
                assertThat(local.refs()).containsEntry(
                        "refs/remotes/upstream/main",
                        seed.main());
                assertThat(local.refs()).containsEntry(
                        "refs/remotes/upstream/release",
                        seed.release());

                assertThat(local.updateRef("refs/heads/main", NULL_ID, seed.main()).status())
                        .isEqualTo(RefUpdateResult.Status.APPLIED);
                local.saveFiles(
                        "main",
                        Map.of("orion.txt", GitFile.regular("outbound\n".getBytes())), Set.of(),
                        "Orion outbound",
                        new GitCommitAuthor("Orion", "orion@example.invalid"));
                String desired = local.refs().get("refs/heads/main");

                assertThat(gateway.pushHead(
                        local,
                        "refs/heads/main",
                        "f".repeat(40),
                        desired).status())
                        .isEqualTo(GitPushOutcome.Status.REMOTE_CHANGED);
                assertThat(gateway.pushHead(
                        local,
                        "refs/heads/main",
                        seed.main(),
                        desired).status())
                        .isEqualTo(GitPushOutcome.Status.APPLIED);
                assertThat(local.refs()).containsEntry(
                        "refs/remotes/upstream/main",
                        desired);
                assertThat(gateway.pushHead(
                        local,
                        "refs/heads/main",
                        seed.main(),
                        desired).status())
                        .isEqualTo(GitPushOutcome.Status.ALREADY_CURRENT);

                try (Repository checked = new FileRepositoryBuilder()
                        .setGitDir(remote.directory().toFile())
                        .build()) {
                    assertThat(checked.exactRef("refs/heads/main").getObjectId().name())
                            .isEqualTo(desired);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesTrackingPublicationAfterAConcurrentChange(
            boolean remoteAlreadyCurrent,
            @TempDir Path temporaryDirectory) throws Exception {
        try (GitServer server = GitServers.jgit()) {
            GitRemoteRepository remote = server.createRemoteRepository(temporaryDirectory, "upstream.git");
            Seed seed = seedRemote(temporaryDirectory, remote);
            try (RacingRepository local = new RacingRepository(temporaryDirectory.resolve("orion"));
                 SmartHttpGitRemoteGateway gateway = gateway(remote)) {
                gateway.fetchHeads(local);
                assertThat(local.updateRef("refs/heads/main", NULL_ID, seed.main()).status())
                        .isEqualTo(RefUpdateResult.Status.APPLIED);
                local.saveFiles(
                        "main", Map.of("outbound.txt", GitFile.regular("outbound\n".getBytes())), Set.of(),
                        "Outbound", new GitCommitAuthor("Orion", "orion@example.invalid"));
                String desired = local.refs().get("refs/heads/main");
                String trackingRef = "refs/remotes/upstream/main";
                if (remoteAlreadyCurrent) {
                    assertThat(gateway.pushHead(local, "refs/heads/main", seed.main(), desired).status())
                            .isEqualTo(GitPushOutcome.Status.APPLIED);
                    assertThat(local.updateRef(trackingRef, desired, seed.main()).status())
                            .isEqualTo(RefUpdateResult.Status.APPLIED);
                }
                local.concurrentUpdate = RefUpdate.fromWire(trackingRef, seed.main(), seed.release());

                GitRemoteException failure = assertThrows(GitRemoteException.class,
                        () -> gateway.pushHead(local, "refs/heads/main", seed.main(), desired));

                assertThat(failure.retryable()).isTrue();
                assertThat(failure.clientKind()).isEmpty();
                assertThat(local.refs()).containsEntry(trackingRef, seed.release());
                assertThat(gateway.listHeads().heads()).containsEntry("refs/heads/main", desired);

                assertThat(gateway.pushHead(local, "refs/heads/main", seed.main(), desired).status())
                        .isEqualTo(GitPushOutcome.Status.ALREADY_CURRENT);
                assertThat(local.refs()).containsEntry(trackingRef, desired)
                        .containsEntry("refs/remotes/upstream/release", seed.release());
            }
        }
    }

    @Test
    void keepsFetchTrackingPublicationAtomicWhenOneRefChanges(
            @TempDir Path temporaryDirectory) throws Exception {
        try (GitServer server = GitServers.jgit()) {
            GitRemoteRepository remote = server.createRemoteRepository(temporaryDirectory, "upstream.git");
            Seed seed = seedRemote(temporaryDirectory, remote);
            try (RacingRepository local = new RacingRepository(temporaryDirectory.resolve("orion"));
                 SmartHttpGitRemoteGateway gateway = gateway(remote)) {
                String trackingRef = "refs/remotes/upstream/main";
                local.concurrentUpdate = RefUpdate.fromWire(trackingRef, NULL_ID, seed.release());

                GitRemoteException failure = assertThrows(GitRemoteException.class,
                        () -> gateway.fetchHeads(local));

                assertThat(failure.retryable()).isTrue();
                assertThat(failure.clientKind()).isEmpty();
                assertThat(local.refs()).containsExactlyInAnyOrderEntriesOf(Map.of(trackingRef, seed.release()));

                assertThat(gateway.fetchHeads(local).heads()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "refs/heads/main", seed.main(), "refs/heads/release", seed.release()));
                assertThat(local.refs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        trackingRef, seed.main(), "refs/remotes/upstream/release", seed.release()));
            }
        }
    }

    private static SmartHttpGitRemoteGateway gateway(GitRemoteRepository remote) {
        GitTcpClientTransport transport = new GitTcpClientTransport();
        GitRemoteConnection connection = new GitRemoteConnection(
                URI.create(remote.uri()),
                GitClientOptions.defaults(),
                new GitUploadPackClient(transport),
                new GitReceivePackClient(transport),
                () -> { });
        return new SmartHttpGitRemoteGateway(connection);
    }

    private static NativeGitRepository nativeRepository(Path directory) throws Exception {
        Path gitDirectory = directory.resolve(".git");
        Files.createDirectories(gitDirectory);
        return new NativeGitRepository(
                "project",
                new GitStorageApi(gitDirectory),
                "refs/heads/main");
    }

    private static Seed seedRemote(
            Path temporaryDirectory,
            GitRemoteRepository remote) throws Exception {
        Path seedDirectory = temporaryDirectory.resolve("seed");
        try (Git seed = Git.init()
                .setDirectory(seedDirectory.toFile())
                .setInitialBranch("main")
                .call()) {
            ObjectId main = commit(seed, seedDirectory, "main.txt", "main\n");
            seed.checkout().setCreateBranch(true).setName("release").call();
            ObjectId release = commit(
                    seed,
                    seedDirectory,
                    "release.txt",
                    "release\n");
            seed.push()
                    .setRemote(remote.directory().toUri().toString())
                    .add("refs/heads/main:refs/heads/main")
                    .add("refs/heads/release:refs/heads/release")
                    .call();
            return new Seed(main.name(), release.name());
        }
    }

    private static ObjectId commit(
            Git git,
            Path directory,
            String file,
            String content) throws Exception {
        Files.writeString(directory.resolve(file), content);
        git.add().addFilepattern(file).call();
        return git.commit()
                .setMessage(file)
                .setAuthor("Orion Test", "orion@example.invalid")
                .setCommitter("Orion Test", "orion@example.invalid")
                .call();
    }

    private static final class RacingRepository extends NativeGitRepository {
        private RefUpdate concurrentUpdate;

        private RacingRepository(Path directory) throws IOException {
            super("project", new GitStorageApi(Files.createDirectories(directory.resolve(".git"))),
                    "refs/heads/main");
        }

        @Override
        public List<RefUpdateResult> publishRefs(List<RefUpdate> updates, boolean atomic) {
            if (concurrentUpdate != null) {
                RefUpdate update = concurrentUpdate;
                concurrentUpdate = null;
                assertThat(super.publishRefs(List.of(update), true).getFirst().status())
                        .isEqualTo(RefUpdateResult.Status.APPLIED);
            }
            return super.publishRefs(updates, atomic);
        }
    }

    private record Seed(String main, String release) {
    }
}
