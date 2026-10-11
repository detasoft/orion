package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import pro.deta.orion.test.integration.git.FileTestSupport;

class NativeGitReceivePackTest {
    private static final String ZERO = "0".repeat(40);
    private static final String MAIN = "refs/heads/main";
    private static final String PROTECTED = "refs/heads/protected";

    @Test
    void authorizesEachRefAndHonorsAtomicAndIndependentUpdates() throws Exception {
        for (boolean atomic : List.of(true, false)) {
            NativeGitRepository repository = repository();
            FileTestSupport.Prepared prepared = prepare(repository, "initial");
            String newId = prepared.refUpdates().getFirst().newId().orElseThrow().toHex();
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

            List<RefUpdateResult> statuses = repository.publishPack(prepared.pack(), List.of(
                    RefUpdate.fromWire(MAIN, ZERO, newId),
                    RefUpdate.fromWire(PROTECTED, ZERO, newId)), atomic, hook);

            assertThat(statuses).extracting(RefUpdateResult::status).containsExactly(
                    atomic ? RefUpdateResult.Status.ATOMIC_ABORTED : RefUpdateResult.Status.APPLIED,
                    RefUpdateResult.Status.REJECTED);
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
        FileTestSupport.Prepared prepared = prepare(repository, "initial");
        GitNativeRepositoryAccessHook denied = new GitNativeRepositoryAccessHook() {
            @Override
            public void beforeWrite(String name) {
                throw new AccessDeniedException("denied", null);
            }
        };

        assertThatThrownBy(() -> repository.publishPack(
                prepared.pack(), prepared.refUpdates(), true, denied))
                .isInstanceOf(GitNativeRepositoryAccessHook.AccessDeniedException.class);
        repository.index().withAccess(access1 -> {
            assertThat(access1.packs()).isEmpty();
            assertThat(repository.refs()).isEmpty();
            return null;
        });
    }

    @Test
    void allowAllStillRejectsAStaleOldId() throws Exception {
        NativeGitRepository repository = repository();
        repository.files().withAccess(MAIN, "initial", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("initial"));
            fileAccess.apply();
            return null;
        });
        FileTestSupport.Prepared stale = prepare(repository, "stale");
        repository.files().withAccess(MAIN, "concurrent", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("concurrent"));
            fileAccess.apply();
            return null;
        });
        String current = repository.refs().get(MAIN);

        assertThat(repository.publishPack(stale.pack(), stale.refUpdates(), true,
                GitNativeRepositoryAccessHook.ALLOW_ALL))
                .extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThat(repository.refs()).containsEntry(MAIN, current);
    }

    @Test
    void rejectsMissingObjectClosureWithAllowAll() throws Exception {
        NativeGitRepository repository = repository();
        FileTestSupport.Prepared prepared = prepare(repository, "initial");

        assertThat(repository.publishPack(prepared.pack(),
                List.of(RefUpdate.fromWire(MAIN, ZERO, "1".repeat(40))), true,
                GitNativeRepositoryAccessHook.ALLOW_ALL))
                .extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.OBJECT_NOT_FOUND);
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void checksAllCommitParentsAndRejectsForcedRewrites() throws Exception {
        NativeGitRepository repository = repository();
        repository.files().withAccess(MAIN, "initial", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("initial"));
            fileAccess.apply();
            return null;
        });
        String initial = repository.refs().get(MAIN);
        for (int index = 0; index < 8; index++) {
            String next = "next" + index;
            repository.files().withAccess(MAIN, "next" + index, GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("config.txt", bytes(next));
                fileAccess.apply();
                return null;
            });
        }
        String descendant = repository.refs().get(MAIN);
        repository.files().withAccess("side", "side", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("side"));
            fileAccess.apply();
            return null;
        });
        String side = repository.refs().get("refs/heads/side");
        String treeLine = new String(repository.readObject(new ObjectId(descendant)).orElseThrow().data(),
                java.nio.charset.StandardCharsets.UTF_8).split("\n")[0];
        String merge = repository.writeObject(GitObjectType.COMMIT, (treeLine + "\nparent " + side
                + "\nparent " + descendant + "\nauthor Test <test@example.invalid> 0 +0000\n"
                + "committer Test <test@example.invalid> 0 +0000\n\nmerge\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toHex();
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
                List.of(RefUpdate.fromWire(PROTECTED, initial, descendant)), true, hook))
                .extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.APPLIED);
        assertThat(repository.publishPack(pack,
                List.of(RefUpdate.fromWire(PROTECTED, descendant, merge)), true, hook))
                .extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.APPLIED);
        assertThat(repository.publishPack(pack,
                List.of(RefUpdate.fromWire(PROTECTED, merge, side)), true, hook))
                .extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.REJECTED);
        assertThat(forceChecks).containsExactly(false, false, true);
        assertThat(repository.refs()).containsEntry(PROTECTED, merge);
    }

    @Test
    void comparesOldIdAgainAfterAuthorization() throws Exception {
        NativeGitRepository repository = repository();
        repository.files().withAccess(MAIN, "initial", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("initial"));
            fileAccess.apply();
            return null;
        });
        FileTestSupport.Prepared prepared = prepare(repository, "prepared");
        String expected = repository.refs().get(MAIN);
        repository.files().withAccess("side", "concurrent", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes("concurrent"));
            fileAccess.apply();
            return null;
        });
        String concurrent = repository.refs().get("refs/heads/side");
        GitNativeRepositoryAccessHook hook = new GitNativeRepositoryAccessHook() {
            @Override
            public void beforeUpdate(String name, String refName, boolean force) {
                repository.updateRef(refName, expected, concurrent);
            }
        };

        assertThat(repository.publishPack(prepared.pack(), prepared.refUpdates(), true, hook))
                .extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThat(repository.refs()).containsEntry(MAIN, concurrent);
    }

    private static NativeGitRepository repository() {
        return NativeGitRepositoryProvider.inMemory().create("demo").valueOrFailure("repository");
    }

    private static FileTestSupport.Prepared prepare(NativeGitRepository repository, String value)
            throws Exception {
        return FileTestSupport.prepared(repository.files(), MAIN, value, GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", bytes(value));
            return null;
        });
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
