package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitReferenceClientSemanticsTest {
    @Test
    void bothClientsRejectDivergentFastForwardPullWithoutMovingHead(@TempDir Path directory) throws Exception {
        for (GitClient client : referenceClients()) {
            Path workspace = Files.createDirectories(directory.resolve(client.name()));
            GitRemoteRepository remote = GitRemoteRepository.createBare(workspace.resolve("remote.git"));
            try (GitWorkTree source = client.init(workspace.resolve("source"))) {
                commit(source, "base.txt", "base\n", "base");
                source.addRemote("origin", remote);
                source.push("origin", "main");

                try (GitWorkTree clone = client.clone(remote, workspace.resolve("clone"))) {
                    commit(source, "remote.txt", "remote\n", "remote");
                    source.push("origin", "main");
                    commit(clone, "local.txt", "local\n", "local");
                    String localHead = clone.head();

                    assertThatThrownBy(() -> clone.pull("origin", "main"))
                            .as(client.name() + " divergent pull")
                            .isInstanceOf(Exception.class);
                    assertThat(clone.head()).isEqualTo(localHead);
                    assertThat(workspace.resolve("clone/.git/MERGE_HEAD")).doesNotExist();
                    assertThat(workspace.resolve("clone/.git/rebase-merge")).doesNotExist();
                    assertThat(workspace.resolve("clone/.git/rebase-apply")).doesNotExist();
                }
            }
        }
    }

    @Test
    void bothClientsOverwriteLocalRefWithAnOlderCommit(@TempDir Path directory) throws Exception {
        for (GitClient client : referenceClients()) {
            try (GitWorkTree workTree = client.init(directory.resolve(client.name() + "-rewind"))) {
                commit(workTree, "first.txt", "first\n", "first");
                String first = workTree.head();
                commit(workTree, "second.txt", "second\n", "second");
                workTree.updateRef("refs/heads/feature", "HEAD");

                workTree.updateRef("refs/heads/feature", first);

                assertThat(workTree.snapshot().refs()).containsEntry("refs/heads/feature", first);
            }
        }
    }

    private static List<GitClient> referenceClients() {
        return List.of(GitClients.jgit(), GitClients.git());
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
