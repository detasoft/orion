package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.orion.OrionGitEngines;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.client.GitTransportScheme.*;

class PartialCloneInteroperabilityTest {
    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        for (String version : List.of("1", "2")) {
            for (GitTransportScheme scheme : List.of(GIT, HTTP, SSH)) {
                cases.add(Arguments.of(version, scheme));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "Git v{0} partial clone from Orion over {1}")
    @MethodSource("matrix")
    void omitsBlobsThenFetchesOnlyThoseNeededByEachCheckout(String version, GitTransportScheme scheme)
            throws Exception {
        GitServer selected = switch (scheme) {
            case GIT -> OrionGitEngines.server();
            case HTTP -> OrionGitEngines.httpServer();
            case SSH -> OrionGitEngines.sshServer();
            default -> throw new IllegalArgumentException(scheme.name());
        };
        GitCommandRunner git = new GitCommandRunner("git", Duration.ofSeconds(30));
        try (GitServer server = selected;
             GitWorkTree source = GitClients.jgitAllowAllSsh().init(directory.resolve("source"))) {
            source.writeFile("README.md", "original\n");
            source.writeFile("shared.txt", "shared\n");
            source.add("README.md", "shared.txt");
            source.commit("initial");
            String first = source.head();
            ObjectId original = ObjectId.fromString(git.run(source.directory(), "rev-parse", "HEAD:README.md").trimmed());
            ObjectId shared = ObjectId.fromString(git.run(source.directory(), "rev-parse", "HEAD:shared.txt").trimmed());
            source.writeFile("README.md", "updated\n");
            source.add("README.md");
            source.commit("update");
            String tip = source.head();
            ObjectId updated = ObjectId.fromString(git.run(source.directory(), "rev-parse", "HEAD:README.md").trimmed());
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            source.addRemote("origin", remote);
            source.push("origin", "main");
            RepositorySnapshot before = server.snapshot(remote);
            Path clone = directory.resolve("clone");
            String protocol = "protocol.version=" + version;
            git.run(null, "-c", protocol, "clone", "--filter=blob:none", "--no-checkout",
                    remote.uri(), clone.toString());
            assertThat(git.run(clone, "rev-list", "HEAD").output().lines().toList()).containsExactly(tip, first);
            assertBlobs(clone, List.of(), List.of(original, shared, updated));

            git.run(clone, "-c", protocol, "checkout", "main");
            assertThat(Files.readString(clone.resolve("README.md"))).isEqualTo("updated\n");
            assertThat(Files.readString(clone.resolve("shared.txt"))).isEqualTo("shared\n");
            assertBlobs(clone, List.of(updated, shared), List.of(original));

            git.run(clone, "-c", protocol, "checkout", "--detach", first);
            assertThat(Files.readString(clone.resolve("README.md"))).isEqualTo("original\n");
            assertThat(Files.readString(clone.resolve("shared.txt"))).isEqualTo("shared\n");
            assertBlobs(clone, List.of(original, shared, updated), List.of());
            git.run(clone, "fsck", "--full");
            assertThat(before.difference(server.snapshot(remote))).isNull();
        }
    }

    private static void assertBlobs(Path clone, List<ObjectId> present, List<ObjectId> absent) throws Exception {
        try (Git local = Git.open(clone.toFile())) {
            for (ObjectId id : present) {
                assertThat(local.getRepository().getObjectDatabase().has(id)).as("present blob %s", id.name()).isTrue();
            }
            for (ObjectId id : absent) {
                assertThat(local.getRepository().getObjectDatabase().has(id)).as("absent blob %s", id.name()).isFalse();
            }
        }
    }
}
