package pro.deta.orion.git.client;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitFileClientTransportTest {
    @TempDir
    private Path tempDir;

    @Test
    void discoversRefsThroughLocalUploadPackProcess() throws Exception {
        Path worktree = tempDir.resolve("seed");
        Path bare = tempDir.resolve("upstream.git");
        try (Git seed = Git.init().setDirectory(worktree.toFile()).setInitialBranch("main").call()) {
            Files.writeString(worktree.resolve("orion.xml"), "configuration");
            seed.add().addFilepattern("orion.xml").call();
            seed.commit().setMessage("seed").setAuthor("Test", "test@example.invalid").call();
            try (Git ignored = Git.cloneRepository()
                    .setURI(worktree.toUri().toString())
                    .setDirectory(bare.toFile())
                    .setBare(true)
                    .call()) {
                // Bare fixture is ready.
            }
        }

        GitClientResult<GitRemoteAdvertisement> result = new GitUploadPackClient(
                new GitFileClientTransport()).discover(bare.toUri(), GitClientOptions.defaults());

        assertThat(result).isInstanceOf(GitClientResult.Success.class);
        assertThat(((GitClientResult.Success<GitRemoteAdvertisement>) result).value().refs())
                .extracting(GitRemoteAdvertisement.Ref::name)
                .contains("refs/heads/main");
    }

    @Test
    void reportsUnavailableRepositoryWithoutEchoingItsPath() {
        Path missing = tempDir.resolve("secret-named-missing.git");

        GitClientResult<GitRemoteAdvertisement> result = new GitUploadPackClient(
                new GitFileClientTransport()).discover(missing.toUri(), GitClientOptions.defaults());

        assertThat(result).isInstanceOf(GitClientResult.Failed.class);
        GitClientFailure failure = ((GitClientResult.Failed<GitRemoteAdvertisement>) result).failure();
        assertThat(failure.kind()).isEqualTo(GitClientFailure.Kind.TRANSPORT_UNAVAILABLE);
        assertThat(failure.message()).doesNotContain("secret-named-missing");
    }

    @Test
    void remoteTransportDispatchesFileUris() throws Exception {
        Path bare = createRepository("dispatch");
        GitClientTransport unsupported = (service, uri, options) -> {
            throw new AssertionError("wrong transport");
        };
        GitRemoteClientTransport transport = new GitRemoteClientTransport(
                new GitFileClientTransport(), unsupported, unsupported, unsupported);

        GitClientResult<GitRemoteAdvertisement> result = new GitUploadPackClient(transport)
                .discover(bare.toUri(), GitClientOptions.defaults());

        assertThat(result).isInstanceOf(GitClientResult.Success.class);
    }

    private Path createRepository(String name) throws Exception {
        Path worktree = tempDir.resolve(name + "-seed");
        Path bare = tempDir.resolve(name + ".git");
        try (Git seed = Git.init().setDirectory(worktree.toFile()).setInitialBranch("main").call()) {
            Files.writeString(worktree.resolve("orion.xml"), "configuration");
            seed.add().addFilepattern("orion.xml").call();
            seed.commit().setMessage("seed").setAuthor("Test", "test@example.invalid").call();
            try (Git ignored = Git.cloneRepository()
                    .setURI(worktree.toUri().toString())
                    .setDirectory(bare.toFile())
                    .setBare(true)
                    .call()) {
                // Bare fixture is ready.
            }
        }
        return bare;
    }
}
