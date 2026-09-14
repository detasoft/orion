package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.ReceivePackStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitReceivePackTest {
    private static final String ZERO = "0".repeat(40);
    private static final String MAIN = "refs/heads/main";
    private static final String PROTECTED = "refs/heads/protected";

    @Test
    void authorizesEachRefAndHonorsAtomicAndIndependentUpdates() throws Exception {
        for (boolean atomic : List.of(true, false)) {
            NativeGitRepository repository = repository();
            NativeGitFileUpdate prepared = prepare(repository, "initial");
            String newId = prepared.refUpdates().getFirst().newId();
            List<String> calls = new ArrayList<>();
            GitNativeRepositoryAccessHook hook = new GitNativeRepositoryAccessHook() {
                @Override
                public void beforeReceive(String name) {
                    calls.add("receive:" + name);
                }

                @Override
                public void beforeWrite(String name) {
                    calls.add("write:" + name);
                }

                @Override
                public void beforeUpdate(String name, String refName, boolean force) {
                    calls.add(refName + ":" + force);
                    if (refName.equals(PROTECTED)) {
                        throw new AccessDeniedException("protected", null);
                    }
                }
            };

            List<ReceivePackStatus> statuses = repository.publishPack(prepared.pack(), List.of(
                    new LooseRefStore.Update(MAIN, ZERO, newId),
                    new LooseRefStore.Update(PROTECTED, ZERO, newId)), atomic, hook);

            assertThat(statuses).containsExactly(
                    new ReceivePackStatus(MAIN, !atomic, atomic ? "atomic-push-failure" : ""),
                    new ReceivePackStatus(PROTECTED, false, "ACCESS_DENIED"));
            assertThat(repository.refs()).doesNotContainKey(PROTECTED);
            if (atomic) {
                assertThat(repository.refs()).isEmpty();
            } else {
                assertThat(repository.refs()).containsEntry(MAIN, newId);
            }
            assertThat(calls).containsExactly("receive:demo", "write:demo", MAIN + ":false", PROTECTED + ":false");
        }
    }

    @Test
    void checksRepositoryAccessBeforeIngestingThePack() throws Exception {
        NativeGitRepository repository = repository();
        NativeGitFileUpdate prepared = prepare(repository, "initial");
        GitNativeRepositoryAccessHook denied = new GitNativeRepositoryAccessHook() {
            @Override
            public void beforeWrite(String name) {
                throw new AccessDeniedException("denied", null);
            }
        };

        assertThatThrownBy(() -> repository.publishPack(
                prepared.pack(), prepared.refUpdates(), true, denied))
                .isInstanceOf(GitNativeRepositoryAccessHook.AccessDeniedException.class);
        assertThat(repository.publishedPacks()).isEmpty();
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void allowAllStillRejectsAStaleOldId() throws Exception {
        NativeGitRepository repository = repository();
        repository.saveFiles(MAIN, files("initial"), "initial", GitCommitAuthor.EMPTY);
        NativeGitFileUpdate stale = prepare(repository, "stale");
        repository.saveFiles(MAIN, files("concurrent"), "concurrent", GitCommitAuthor.EMPTY);
        String current = repository.refs().get(MAIN);

        assertThat(repository.publishPack(stale.pack(), stale.refUpdates(), true,
                GitNativeRepositoryAccessHook.ALLOW_ALL))
                .containsExactly(new ReceivePackStatus(MAIN, false, "stale"));
        assertThat(repository.refs()).containsEntry(MAIN, current);
    }

    @Test
    void rejectsMissingObjectClosureWithAllowAll() throws Exception {
        NativeGitRepository repository = repository();
        NativeGitFileUpdate prepared = prepare(repository, "initial");

        assertThat(repository.publishPack(prepared.pack(),
                List.of(new LooseRefStore.Update(MAIN, ZERO, "1".repeat(40))), true,
                GitNativeRepositoryAccessHook.ALLOW_ALL))
                .containsExactly(new ReceivePackStatus(MAIN, false, "missing-necessary-objects"));
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void checksAllCommitParentsAndRejectsForcedRewrites() throws Exception {
        NativeGitRepository repository = repository();
        repository.saveFiles(MAIN, files("initial"), "initial", GitCommitAuthor.EMPTY);
        String initial = repository.refs().get(MAIN);
        for (int index = 0; index < 8; index++) {
            repository.saveFiles(MAIN, files("next" + index), "next" + index, GitCommitAuthor.EMPTY);
        }
        String descendant = repository.refs().get(MAIN);
        repository.saveFiles("side", files("side"), "side", GitCommitAuthor.EMPTY);
        String side = repository.refs().get("refs/heads/side");
        String treeLine = new String(repository.readObject(GitObjectId.of(descendant)).orElseThrow().data(),
                java.nio.charset.StandardCharsets.UTF_8).split("\n")[0];
        String merge = repository.writeObject(ObjectType.COMMIT, (treeLine + "\nparent " + side
                + "\nparent " + descendant + "\nauthor Test <test@example.invalid> 0 +0000\n"
                + "committer Test <test@example.invalid> 0 +0000\n\nmerge\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).value();
        repository.updateRef(PROTECTED, ZERO, initial);
        List<Boolean> forceChecks = new ArrayList<>();
        GitNativeRepositoryAccessHook hook = new GitNativeRepositoryAccessHook() {
            @Override
            public void beforeUpdate(String name, String refName, boolean force) {
                forceChecks.add(force);
                if (force) {
                    throw new AccessDeniedException("force denied", null);
                }
            }
        };
        byte[] pack = prepare(repository, "pack").pack();

        assertThat(repository.publishPack(pack,
                List.of(new LooseRefStore.Update(PROTECTED, initial, descendant)), true, hook))
                .containsExactly(new ReceivePackStatus(PROTECTED, true, ""));
        assertThat(repository.publishPack(pack,
                List.of(new LooseRefStore.Update(PROTECTED, descendant, merge)), true, hook))
                .containsExactly(new ReceivePackStatus(PROTECTED, true, ""));
        assertThat(repository.publishPack(pack,
                List.of(new LooseRefStore.Update(PROTECTED, merge, side)), true, hook))
                .containsExactly(new ReceivePackStatus(PROTECTED, false, "ACCESS_DENIED"));
        assertThat(forceChecks).containsExactly(false, false, true);
        assertThat(repository.refs()).containsEntry(PROTECTED, merge);
    }

    @Test
    void comparesOldIdAgainAfterAuthorization() throws Exception {
        NativeGitRepository repository = repository();
        repository.saveFiles(MAIN, files("initial"), "initial", GitCommitAuthor.EMPTY);
        NativeGitFileUpdate prepared = prepare(repository, "prepared");
        String expected = repository.refs().get(MAIN);
        repository.saveFiles("side", files("concurrent"), "concurrent", GitCommitAuthor.EMPTY);
        String concurrent = repository.refs().get("refs/heads/side");
        GitNativeRepositoryAccessHook hook = new GitNativeRepositoryAccessHook() {
            @Override
            public void beforeUpdate(String name, String refName, boolean force) {
                repository.updateRef(refName, expected, concurrent);
            }
        };

        assertThat(repository.publishPack(prepared.pack(), prepared.refUpdates(), true, hook))
                .containsExactly(new ReceivePackStatus(MAIN, false, "stale"));
        assertThat(repository.refs()).containsEntry(MAIN, concurrent);
    }

    private static NativeGitRepository repository() {
        return new InMemoryNativeGitRepositoryProvider().create("demo").valueOrFailure("repository");
    }

    private static NativeGitFileUpdate prepare(NativeGitRepository repository, String value)
            throws GitOperationException {
        return repository.prepareFileUpdate(MAIN, files(value), value, GitCommitAuthor.EMPTY);
    }

    private static Map<String, byte[]> files(String value) {
        return Map.of("config.txt", value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
