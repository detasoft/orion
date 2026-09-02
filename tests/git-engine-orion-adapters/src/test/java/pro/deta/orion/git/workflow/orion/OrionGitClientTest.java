package pro.deta.orion.git.workflow.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitServers;
import pro.deta.orion.git.workflow.GitWorkTree;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionGitClientTest {
    @Test
    void createsTheSameDeterministicCommitAsJGitAndUpdatesLocalRefs(@TempDir Path directory) throws Exception {
        try (GitWorkTree orion = OrionGitEngines.client().init(directory.resolve("orion"));
                GitWorkTree jgit = GitClients.jgit().init(directory.resolve("jgit"))) {
            commit(orion, "README.md", "deterministic\n", "initial");
            commit(jgit, "README.md", "deterministic\n", "initial");

            assertThat(orion.head()).isEqualTo(jgit.head());
            assertThat(orion.snapshot().difference(jgit.snapshot())).isNull();

            orion.updateRef("refs/heads/feature", "HEAD");
            assertThat(orion.snapshot().refs()).containsEntry("refs/heads/feature", orion.head());
        }
    }

    @Test
    void failsCommitWhenAStagedPathIsMissing(@TempDir Path directory) throws Exception {
        try (GitWorkTree workTree = OrionGitEngines.client().init(directory.resolve("orion"))) {
            workTree.add("missing.txt");

            assertThatThrownBy(() -> workTree.commit("missing"))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("missing.txt");
        }
    }

    @Test
    void pushesClonesFetchesAndPullsThroughNativeClientPrimitives(@TempDir Path directory) throws Exception {
        try (GitServer server = GitServers.jgit()) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            try (GitWorkTree source = OrionGitEngines.client().init(directory.resolve("source"))) {
                commit(source, "README.md", "initial\n", "initial");
                source.addRemote("origin", remote);
                source.push("origin", "main");

                try (GitWorkTree clone = OrionGitEngines.client().clone(remote, directory.resolve("clone"))) {
                    assertThat(clone.head()).isEqualTo(source.head());

                    commit(source, "README.md", "updated\n", "updated");
                    source.push("origin", "main");

                    clone.fetch("origin");
                    clone.updateRef("refs/heads/fetched", "refs/remotes/origin/main");
                    clone.pull("origin", "main");
                    assertThat(clone.head()).isEqualTo(source.head());
                    assertThat(clone.snapshot().commits()).isEqualTo(source.snapshot().commits());

                    source.updateRef("refs/heads/feature", "HEAD");
                    source.updateRef("refs/tags/v1", "HEAD");
                    source.pushRefs(
                            "origin",
                            "refs/heads/feature:refs/heads/feature",
                            "refs/tags/v1:refs/tags/v1");
                    assertThat(server.snapshot(remote).refs())
                            .containsEntry("refs/heads/feature", source.head())
                            .containsEntry("refs/tags/v1", source.head());
                }
            }
        }
    }

    @Test
    void rejectsDivergentPullWithoutMovingHead(@TempDir Path directory) throws Exception {
        try (GitServer server = GitServers.jgit()) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            try (GitWorkTree source = OrionGitEngines.client().init(directory.resolve("source"))) {
                commit(source, "base.txt", "base\n", "base");
                source.addRemote("origin", remote);
                source.push("origin", "main");

                try (GitWorkTree clone = OrionGitEngines.client().clone(remote, directory.resolve("clone"))) {
                    commit(source, "remote.txt", "remote\n", "remote");
                    source.push("origin", "main");
                    commit(clone, "local.txt", "local\n", "local");
                    String localHead = clone.head();

                    assertThatThrownBy(() -> clone.pull("origin", "main"))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("fast-forward");
                    assertThat(clone.head()).isEqualTo(localHead);
                }
            }
        }
    }

    @Test
    void reportsARealTransportFailureAsAnAdapterFailure(@TempDir Path directory) {
        assertThatThrownBy(() -> OrionGitEngines.client().clone(
                "git://127.0.0.1:1/missing.git",
                directory.resolve("clone")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Orion upload-pack discovery failed")
                .hasMessageContaining("TRANSPORT_UNAVAILABLE");
    }

    @Test
    void reportsARejectedReceivePackResultAsAnAdapterFailure() {
        GitReceivePackResult rejected = new GitReceivePackResult(
                new GitRemoteAdvertisement(Set.of(), List.of()),
                "ok",
                List.of(new GitReceivePackResult.RefStatus(
                        "refs/heads/main", false, "non-fast-forward")));

        assertThatThrownBy(() -> OrionGitClient.requireAccepted(rejected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("push was rejected")
                .hasMessageContaining("refs/heads/main")
                .hasMessageContaining("non-fast-forward");
    }

    @Test
    void rejectsDivergentPushWithoutChangingTheRemote(@TempDir Path directory) throws Exception {
        try (GitServer server = OrionGitEngines.server()) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            try (GitWorkTree source = OrionGitEngines.client().init(directory.resolve("source"))) {
                commit(source, "base.txt", "base\n", "base");
                source.addRemote("origin", remote);
                source.push("origin", "main");

                try (GitWorkTree stale = OrionGitEngines.client().clone(remote, directory.resolve("stale"))) {
                    commit(source, "accepted.txt", "accepted\n", "accepted");
                    source.push("origin", "main");
                    commit(stale, "rejected.txt", "rejected\n", "rejected");

                    assertThatThrownBy(() -> stale.push("origin", "main"))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("non-fast-forward")
                            .hasMessageContaining("refs/heads/main");
                    assertThat(server.snapshot(remote).refs())
                            .containsEntry("refs/heads/main", source.head());
                }
            }
        }
    }

    @Test
    void rejectsForcedRefspecsExplicitly(@TempDir Path directory) throws Exception {
        try (GitServer server = GitServers.jgit();
                GitWorkTree source = OrionGitEngines.client().init(directory.resolve("source"))) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            commit(source, "README.md", "initial\n", "initial");
            source.addRemote("origin", remote);

            assertThatThrownBy(() -> source.pushRefs(
                    "origin", "+refs/heads/main:refs/heads/main"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("forced refspecs");
        }
    }

    private static void commit(
            GitWorkTree workTree,
            String path,
            String content,
            String message) throws Exception {
        workTree.writeFile(path, content);
        workTree.add(path);
        workTree.commit(message);
    }
}
