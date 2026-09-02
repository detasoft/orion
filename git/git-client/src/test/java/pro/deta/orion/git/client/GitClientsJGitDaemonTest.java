package pro.deta.orion.git.client;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitServers;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GitClientsJGitDaemonTest {
    @Test
    void fetchesAndPushesAgainstJGitDaemonOnOsAssignedPort(
            @TempDir Path temporaryDirectory) throws Exception {
        ObjectId updatedCommit;
        GitRemoteRepository remote;
        TestRepository testRepository;
        try (GitServer server = GitServers.jgit()) {
            remote = server.createRemoteRepository(temporaryDirectory, "test.git");
            testRepository = createRepository(temporaryDirectory, remote);
            URI repositoryUri = URI.create(remote.uri());
            assertThat(repositoryUri.getPort()).isPositive();
            GitClientOptions options = GitClientOptions.defaults();

            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            GitClientTransport transport = new GitTcpClientTransport();
            GitClientResult<GitUploadPackResult> fetch =
                    new GitUploadPackClient(transport).fetch(
                            repositoryUri,
                            options,
                            GitUploadPackRequest.of(
                                    testRepository.commitId(),
                                    new OutputStreamBufferedByteOutput(pack)));

            assertThat(fetch).isInstanceOf(GitClientResult.Success.class);
            GitUploadPackResult fetchResult = success(fetch);
            assertThat(fetchResult.packBytes()).isPositive();
            assertThat(pack.toByteArray()).startsWith(
                    "PACK".getBytes(StandardCharsets.US_ASCII));

            ObjectId createdCommit = commit(testRepository.sourcePath(),
                    "Create branch through receive-pack\n");
            GitClientResult<GitReceivePackResult> createPush =
                    new GitReceivePackClient(transport).push(
                            repositoryUri, options, receiveRequest(
                                    GitClientValidation.NULL_ID,
                                    createdCommit.name(),
                                    "refs/heads/pushed-with-pack",
                                    pack(testRepository.sourcePath(),
                                            createdCommit,
                                            ObjectId.fromString(
                                                    testRepository.commitId()))));

            assertThat(createPush).isInstanceOf(GitClientResult.Success.class);
            assertThat(success(createPush).accepted()).isTrue();

            updatedCommit = commit(testRepository.sourcePath(),
                    "Update branch through receive-pack\n");
            GitReceivePackRequest updateBranch = receiveRequest(
                    createdCommit.name(),
                    updatedCommit.name(),
                    "refs/heads/pushed-with-pack",
                    pack(testRepository.sourcePath(), updatedCommit, createdCommit));
            GitClientResult<GitReceivePackResult> push =
                    new GitReceivePackClient(transport).push(
                            repositoryUri, options, updateBranch);

            assertThat(push).isInstanceOf(GitClientResult.Success.class);
            assertThat(success(push).accepted()).isTrue();
        }

        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(testRepository.path().toFile())
                .build()) {
            assertThat(repository.exactRef("refs/heads/pushed-with-pack"))
                    .extracting(ref -> ref.getObjectId().name())
                    .isEqualTo(updatedCommit.name());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T success(GitClientResult<T> result) {
        return ((GitClientResult.Success<T>) result).value();
    }

    private static TestRepository createRepository(
            Path temporaryDirectory,
            GitRemoteRepository remote)
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
            seed.push()
                    .setRemote(remote.directory().toUri().toString())
                    .add("refs/heads/main:refs/heads/main")
                    .call();
        }
        return new TestRepository(seedDirectory, remote.directory(), commitId.name());
    }

    private static ObjectId commit(Path sourcePath, String contents)
            throws Exception {
        try (Git source = Git.open(sourcePath.toFile())) {
            Files.writeString(sourcePath.resolve("README.md"), contents);
            source.add().addFilepattern("README.md").call();
            return source.commit()
                    .setMessage(contents.strip())
                    .setAuthor("Orion Test", "orion@example.invalid")
                    .setCommitter("Orion Test", "orion@example.invalid")
                    .call();
        }
    }

    private static GitReceivePackRequest receiveRequest(
            String oldObjectId,
            String newObjectId,
            String refName,
            byte[] pack) {
        return new GitReceivePackRequest(
                List.of(new GitReceivePackRequest.Command(
                        oldObjectId, newObjectId, refName)),
                output -> output.write(pack));
    }

    private static byte[] pack(
            Path sourcePath,
            ObjectId want,
            ObjectId have) throws Exception {
        try (Git source = Git.open(sourcePath.toFile());
                PackWriter writer = new PackWriter(source.getRepository());
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writer.preparePack(
                    NullProgressMonitor.INSTANCE, Set.of(want), Set.of(have));
            writer.writePack(NullProgressMonitor.INSTANCE,
                    NullProgressMonitor.INSTANCE, output);
            return output.toByteArray();
        }
    }

    private record TestRepository(Path sourcePath, Path path, String commitId) {
    }
}
