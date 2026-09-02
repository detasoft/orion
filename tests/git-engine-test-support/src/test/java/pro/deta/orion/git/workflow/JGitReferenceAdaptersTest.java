package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JGitReferenceAdaptersTest {
    @Test
    void cloneConfiguresDeterministicCheckoutBeforePopulatingMain(@TempDir Path directory) throws Exception {
        GitRemoteRepository remote = GitRemoteRepository.createBare(directory.resolve("remote.git"));
        try (GitWorkTree source = GitClients.jgit().init(directory.resolve("source"))) {
            source.writeFile("lines.txt", "first\nsecond\n");
            source.add("lines.txt");
            source.commit("initial");
            source.addRemote("origin", remote);
            source.push("origin", "main");
        }

        SystemReader original = SystemReader.getInstance();
        SystemReader.setInstance(systemReaderWithInvalidAutoCrlf(original));
        try (GitWorkTree clone = GitClients.jgit().clone(remote, directory.resolve("clone"));
                var repository = new FileRepositoryBuilder()
                        .setGitDir(directory.resolve("clone/.git").toFile())
                        .build()) {
            assertThat(repository.getFullBranch()).isEqualTo("refs/heads/main");
            assertThat(repository.getConfig().getBoolean("commit", null, "gpgSign", true)).isFalse();
            assertThat(repository.getConfig().getBoolean("core", null, "autocrlf", true)).isFalse();
            assertThat(repository.getConfig().getBoolean("core", null, "fileMode", true)).isFalse();
            assertThat(Files.readAllBytes(directory.resolve("clone/lines.txt")))
                    .isEqualTo("first\nsecond\n".getBytes(StandardCharsets.UTF_8));
        } finally {
            SystemReader.setInstance(original);
        }
    }

    @Test
    void servesReceivePackOnAnOsAssignedLoopbackPort(@TempDir Path directory) throws Exception {
        GitServer server = GitServers.jgit();

        try (server) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            assertThat(java.net.URI.create(remote.uri()).getPort()).isPositive();
            try (GitWorkTree source = GitClients.jgit().init(directory.resolve("source"))) {
                source.writeFile("README.md", "served by JGit\n");
                source.add("README.md");
                source.commit("initial");
                source.addRemote("origin", remote);
                source.push("origin", "main");
            }

            assertThat(server.snapshot(remote).refs()).containsKey("refs/heads/main");
            assertThat(server.diagnostics()).startsWith("JGit/");
        }
    }

    @Test
    void updatesLocalRefsAndPushesSeveralRefspecs(@TempDir Path directory) throws Exception {
        GitRemoteRepository remote = GitRemoteRepository.createBare(directory.resolve("remote.git"));
        try (GitWorkTree source = GitClients.jgit().init(directory.resolve("source"))) {
            source.writeFile("README.md", "refs\n");
            source.add("README.md");
            source.commit("initial");
            source.updateRef("refs/heads/feature", "HEAD");
            source.updateRef("refs/tags/v1", "HEAD");
            source.addRemote("origin", remote);
            source.pushRefs("origin", "refs/heads/feature:refs/heads/feature", "refs/tags/v1:refs/tags/v1");
        }

        assertThat(RepositorySnapshot.capture(remote.directory()).refs())
                .containsKeys("refs/heads/feature", "refs/tags/v1");
    }

    private static SystemReader systemReaderWithInvalidAutoCrlf(SystemReader delegate) {
        return new SystemReader.Delegate(delegate) {
            @Override
            public FileBasedConfig openUserConfig(Config parent, FS fs) {
                return new FileBasedConfig(parent, null, fs) {
                    @Override
                    public void load() {
                        setString("core", null, "autocrlf", "invalid");
                    }

                    @Override
                    public boolean isOutdated() {
                        return true;
                    }
                };
            }
        };
    }
}
