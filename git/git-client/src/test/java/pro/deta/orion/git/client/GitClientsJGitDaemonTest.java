package pro.deta.orion.git.client;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitClientsJGitDaemonTest {
    @Test
    void fetchesAndPushesAgainstJGitDaemonOnOsAssignedPort(
            @TempDir Path temporaryDirectory) throws Exception {
        TestRepository testRepository = createRepository(temporaryDirectory);
        try (JGitDaemonTestServer server = JGitDaemonTestServer.start(
                testRepository.path())) {
            assertThat(server.repositoryUri().getPort()).isPositive();
            GitClientOptions options = GitClientOptions.defaults();

            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            GitClientResult<GitUploadPackResult> fetch =
                    new GitUploadPackClient(server.transport()).fetch(
                            server.repositoryUri(),
                            options,
                            GitUploadPackRequest.of(
                                    testRepository.commitId(),
                                    new OutputStreamBufferedByteOutput(pack)));

            assertThat(fetch).isInstanceOf(GitClientResult.Success.class);
            GitUploadPackResult fetchResult = success(fetch);
            assertThat(fetchResult.packBytes()).isPositive();
            assertThat(pack.toByteArray()).startsWith(
                    "PACK".getBytes(StandardCharsets.US_ASCII));

            GitReceivePackRequest deleteBranch = new GitReceivePackRequest(
                    List.of(new GitReceivePackRequest.Command(
                            testRepository.commitId(),
                            GitClientValidation.NULL_ID,
                            "refs/heads/delete-me")),
                    output -> { });
            GitClientResult<GitReceivePackResult> push =
                    new GitReceivePackClient(server.transport()).push(
                            server.repositoryUri(), options, deleteBranch);

            assertThat(push).isInstanceOf(GitClientResult.Success.class);
            assertThat(success(push).accepted()).isTrue();
        }

        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(testRepository.path().toFile())
                .build()) {
            assertThat(repository.exactRef("refs/heads/delete-me")).isNull();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T success(GitClientResult<T> result) {
        return ((GitClientResult.Success<T>) result).value();
    }

    private static TestRepository createRepository(Path temporaryDirectory)
            throws Exception {
        Path seedDirectory = temporaryDirectory.resolve("seed");
        ObjectId commitId;
        try (Git seed = Git.init()
                .setDirectory(seedDirectory.toFile())
                .setInitialBranch("main")
                .call()) {
            Files.writeString(
                    seedDirectory.resolve("README.md"),
                    "JGit daemon compatibility fixture\n");
            seed.add().addFilepattern("README.md").call();
            commitId = seed.commit()
                    .setMessage("Seed repository")
                    .setAuthor("Orion Test", "orion@example.invalid")
                    .setCommitter("Orion Test", "orion@example.invalid")
                    .call();
        }

        Path bareRepository = temporaryDirectory.resolve("test.git");
        try (Git remote = Git.cloneRepository()
                .setURI(seedDirectory.toUri().toString())
                .setDirectory(bareRepository.toFile())
                .setBare(true)
                .call()) {
            RefUpdate branch = remote.getRepository()
                    .updateRef("refs/heads/delete-me");
            branch.setNewObjectId(commitId);
            assertThat(branch.update()).isEqualTo(RefUpdate.Result.NEW);
        }
        return new TestRepository(bareRepository, commitId.name());
    }

    private record TestRepository(Path path, String commitId) {
    }
}
