package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.orion.OrionGitEngines;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ServerOptionInteroperabilityTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "Git CLI server options over {0}")
    @EnumSource(value = GitTransportScheme.class, names = {"GIT", "HTTP", "SSH"})
    void discoversAndFetchesWithRepeatedSpacedOptions(GitTransportScheme scheme) throws Exception {
        GitServer selected = switch (scheme) {
            case GIT -> OrionGitEngines.server();
            case HTTP -> OrionGitEngines.httpServer();
            case SSH -> OrionGitEngines.sshServer();
            default -> throw new IllegalArgumentException("Unsupported test transport: " + scheme);
        };
        try (GitServer server = selected;
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            source.writeFile("README.md", "server options\n");
            source.add("README.md");
            source.commit("initial");
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.push("origin", "main");
            GitCommandRunner git = new GitCommandRunner("git", Duration.ofSeconds(30));
            assertThat(git.run(directory, "ls-remote", "--server-option=foo bar",
                    "--server-option=foo bar", "--server-option=", remote.uri()).output())
                    .contains(source.head() + "\trefs/heads/main");
            Path fetched = directory.resolve("fetched");
            git.run(null, "init", fetched.toString());
            git.run(fetched, "fetch", "--server-option=foo bar", "--server-option=foo bar",
                    "--server-option=", remote.uri(), "refs/heads/main");
            assertThat(git.run(fetched, "rev-parse", "FETCH_HEAD").trimmed()).isEqualTo(source.head());
            assertThat(git.run(fetched, "show", "FETCH_HEAD:README.md").output()).isEqualTo("server options\n");
        }
    }
}
