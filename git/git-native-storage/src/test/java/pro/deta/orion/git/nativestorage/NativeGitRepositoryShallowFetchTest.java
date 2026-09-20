package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.fetch.FetchPack;
import pro.deta.orion.git.parser.v2.fetch.FetchPlan;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryShallowFetchTest {
    @Test
    void depthAndTimestampStopBeforeTheParent() throws Exception {
        try (NativeGitRepository repository = repository()) {
            ObjectId tree = repository.writeObject(GitObjectType.TREE, new byte[0]);
            ObjectId base = commit(repository, tree, null, 100);
            ObjectId tip = commit(repository, tree, base, 300);
            for (FetchPlan plan : List.of(
                    plan(tip, OptionalInt.of(1), OptionalLong.empty(), Set.of()),
                    plan(tip, OptionalInt.empty(), OptionalLong.of(200), Set.of()))) {
                FetchPack pack = FetchPack.prepare(repository.storage(), plan);
                assertThat(pack.shallowCommits()).containsExactly(tip);
                assertThat(pack.objectCount()).isEqualTo(2);
            }
        }
    }

    @Test
    void excludesHistoryByFullRefShortBranchOrHead() throws Exception {
        try (NativeGitRepository repository = repository()) {
            ObjectId tree = repository.writeObject(GitObjectType.TREE, new byte[0]);
            ObjectId base = commit(repository, tree, null, 100);
            ObjectId tip = commit(repository, tree, base, 300);
            repository.updateRef("refs/heads/main", "0".repeat(40), base.toHex());
            for (String ref : List.of("refs/heads/main", "main", "HEAD")) {
                FetchPack pack = FetchPack.prepare(repository.storage(),
                        plan(tip, OptionalInt.empty(), OptionalLong.empty(), Set.of(ref)));
                assertThat(pack.shallowCommits()).containsExactly(tip);
                assertThat(pack.objectCount()).isEqualTo(2);
            }
        }
    }

    @Test
    void rejectsMissingExcludedRevision() throws Exception {
        try (NativeGitRepository repository = repository()) {
            ObjectId tree = repository.writeObject(GitObjectType.TREE, new byte[0]);
            ObjectId tip = commit(repository, tree, null, 100);
            assertThatThrownBy(() -> FetchPack.prepare(repository.storage(),
                    plan(tip, OptionalInt.empty(), OptionalLong.empty(), Set.of("refs/heads/missing"))))
                    .isInstanceOf(IOException.class).hasMessageContaining("Unknown deepen-not ref");
        }
    }

    private static FetchPlan plan(ObjectId tip, OptionalInt depth, OptionalLong since, Set<String> excluded) {
        return new FetchPlan(Set.of(tip), Map.of(), Set.of(), Set.of(), depth, since, excluded,
                Optional.empty(), new GitCapabilities(), Set.of());
    }

    private static ObjectId commit(NativeGitRepository repository, ObjectId tree,
                                   ObjectId parent, long timestamp) {
        String data = "tree " + tree.toHex() + "\n"
                + (parent == null ? "" : "parent " + parent.toHex() + "\n")
                + "author A <a@b> 0 +0000\ncommitter A <a@b> " + timestamp + " +0000\n\ncommit\n";
        return new ObjectId(repository.writeObject(GitObjectType.COMMIT, data.getBytes(StandardCharsets.UTF_8)).toHex());
    }

    private static NativeGitRepository repository() {
        return new NativeGitRepository("demo", new GitStorageApi(), "refs/heads/main");
    }
}
