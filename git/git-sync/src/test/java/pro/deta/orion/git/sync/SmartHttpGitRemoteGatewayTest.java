package pro.deta.orion.git.sync;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitTcpClientTransport;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitServers;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
                GitFetchedHeads fetched = gateway.fetchHeads(local);

                assertThat(fetched.heads()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "refs/heads/main", seed.main(),
                        "refs/heads/release", seed.release()));
                assertThat(local.refs()).containsEntry(
                        "refs/remotes/upstream/main",
                        seed.main());
                assertThat(local.refs()).containsEntry(
                        "refs/remotes/upstream/release",
                        seed.release());

                assertThat(local.updateRef("refs/heads/main", NULL_ID, seed.main()))
                        .isEqualTo(RefUpdateResult.CREATED);
                local.saveFiles(
                        "main",
                        Map.of("orion.txt", "outbound\n".getBytes()),
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
        Files.createDirectories(gitDirectory.resolve("objects"));
        Files.createDirectories(gitDirectory.resolve("refs"));
        return new NativeGitRepository(
                "project",
                new LooseRefStore(gitDirectory),
                new LooseObjectStore(gitDirectory.resolve("objects")),
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

    private record Seed(String main, String release) {
    }
}
