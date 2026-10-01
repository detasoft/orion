package pro.deta.orion.git.parser.v2.index;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.APPLIED;

class GitIndexAccessRefsTest {
    private static final RefId MAIN = new RefId("refs/heads/main");
    private static final RefId OTHER = new RefId("refs/heads/other");
    private static final ObjectId FIRST = new ObjectId("1".repeat(40));
    private static final ObjectId SECOND = new ObjectId("2".repeat(40));

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectsRefsByExactNamePatternAndLiteralSubstring(boolean local) throws Exception {
        GitIndexApi index = index(local);
        RefId tag = new RefId("refs/tags/main");
        RefId nestedTag = new RefId("refs/tags/releases/main");
        publish(index, MAIN, null, FIRST);
        publish(index, OTHER, null, SECOND);
        publish(index, tag, null, FIRST);
        publish(index, nestedTag, null, SECOND);
        index.withAccess(Set.of(MAIN, tag), access -> {
            stage(access, MAIN, FIRST, SECOND);
            assertThat(access.findRef(MAIN)).contains(SECOND);
            assertThat(access.snapshotRefs(new RefSelection.One(MAIN)).refs())
                    .containsExactlyEntriesOf(Map.of(MAIN, SECOND));
            assertThat(access.snapshotRefs(new RefSelection.Pattern("refs/*/main")).refs())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(MAIN, SECOND, tag, FIRST, nestedTag, SECOND));
            assertThat(access.snapshotRefs(new RefSelection.Pattern("refs/heads/*")).refs())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(MAIN, SECOND, OTHER, SECOND));
            assertThat(access.snapshotRefs(new RefSelection.Pattern("refs/heads")).refs()).isEmpty();
            assertThat(access.snapshotRefs(new RefSelection.Substring("tags/")).refs())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(tag, FIRST, nestedTag, SECOND));
            assertThat(access.snapshotRefs(new RefSelection.Substring("tags/*")).refs()).isEmpty();
            assertThat(access.snapshotRefs(new RefSelection.Pattern("*")).refs()).hasSize(4);
            assertThat(access.snapshotRefs(new RefSelection.All()).refs()).hasSize(4);
            assertThat(access.snapshotRefs(new RefSelection.Head()).refs()).isEmpty();
            assertThat(access.snapshotRefs(new RefSelection.Head()).head())
                    .isEqualTo(access.snapshotRefs(new RefSelection.All()).head());
            assertThat(access.findRef(new RefId("refs/heads/missing"))).isEmpty();
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsStaleExpectationsWhenOpeningAndStillAllowsReopening(boolean local) throws Exception {
        GitIndexApi index = index(local);
        publish(index, MAIN, null, FIRST);
        assertThatThrownBy(() -> index.createAccess(List.of(update(MAIN, null, FIRST))))
                .isInstanceOf(GitRefConflictException.class);
        assertThatThrownBy(() -> index.createAccess(List.of(update(MAIN, SECOND, FIRST))))
                .isInstanceOfSatisfying(GitRefConflictException.class, conflict -> {
                    assertThat(conflict.update()).isEqualTo(update(MAIN, SECOND, FIRST));
                    assertThat(conflict.actual()).contains(FIRST);
        });
        index.withAccess(List.of(update(MAIN, FIRST, SECOND)), access -> {
            access.apply();
            return null;
        });
        assertRefs(index, Map.of(MAIN, SECOND));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refsStayPrivateUntilApplyAndDiscardLeavesThemUnchanged(boolean local) throws Exception {
        GitIndexApi index = index(local);
        index.withAccess(Set.of(MAIN), access -> {
            stage(access, MAIN, null, FIRST);
            assertThat(access.snapshotRefs(new RefSelection.All()).refs()).containsEntry(MAIN, FIRST);
            assertRefs(index, Map.of());
            access.discard();
            access.discard();
            return null;
        });
        assertRefs(index, Map.of());
        publish(index, MAIN, null, FIRST);
        assertRefs(index, Map.of(MAIN, FIRST));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUndeclaredRefsBeforeChangingAnyPendingState(boolean local) throws Exception {
        GitIndexApi index = index(local);
        index.withAccess(access -> {
            assertThatThrownBy(() -> stage(access, MAIN, null, FIRST))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared");
            return null;
        });
        index.withAccess(Set.of(MAIN), access -> {
            assertThatThrownBy(() -> access.updateRefs(List.of(
                    update(MAIN, null, FIRST), update(OTHER, null, FIRST)), true))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared");
            access.apply();
            return null;
        });
        assertRefs(index, Map.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsCapturedValueAndRejectsConcurrentChangeAtApply(boolean local) throws Exception {
        GitIndexApi index = index(local);
        publish(index, MAIN, null, FIRST);
        index.withAccess(Set.of(MAIN, OTHER), access -> {
            publish(index, MAIN, FIRST, SECOND);
            assertThat(access.snapshotRefs(new RefSelection.All()).refs()).containsEntry(MAIN, FIRST);
            access.updateRefs(List.of(update(MAIN, FIRST, null), update(OTHER, null, FIRST)), true);
            assertThatThrownBy(access::apply)
                    .isInstanceOfSatisfying(GitRefConflictException.class, conflict -> {
                        assertThat(conflict.update()).isEqualTo(update(MAIN, FIRST, null));
                        assertThat(conflict.actual()).contains(SECOND);
            });
            assertThatThrownBy(() -> access.snapshotRefs(new RefSelection.All())).isInstanceOf(IOException.class);
            return null;
        });
        assertRefs(index, Map.of(MAIN, SECOND));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distinguishesExpectedAbsenceFromUndeclaredAndDetectsConcurrentCreation(boolean local) throws Exception {
        GitIndexApi index = index(local);
        index.withAccess(List.of(update(MAIN, null, FIRST)), first -> {
            GitIndexAccess second = index.createAccess(List.of(update(MAIN, null, SECOND)));
            try {
                first.apply();
                assertThatThrownBy(second::apply).isInstanceOf(GitRefConflictException.class);
            } finally {
                second.discard();
            }
            return null;
        });
        assertRefs(index, Map.of(MAIN, FIRST));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyChecksModifiedRefsAndPreservesConcurrentUnrelatedUpdates(boolean local) throws Exception {
        GitIndexApi index = index(local);
        index.withAccess(Set.of(MAIN, OTHER), access -> {
            publish(index, OTHER, null, SECOND);
            stage(access, MAIN, null, FIRST);
            access.apply();
            return null;
        });
        assertRefs(index, Map.of(MAIN, FIRST, OTHER, SECOND));
        publish(index, MAIN, FIRST, null);
        assertRefs(index, Map.of(OTHER, SECOND));
    }

    private GitIndexApi index(boolean local) throws IOException {
        return local ? new LocalGitIndex(directory) : new InMemoryIndex();
    }

    @Test
    void concurrentAccessesPublishOnlyOneCompleteBatch() throws Exception {
        try (GitIndexApi firstIndex = new LocalGitIndex(directory);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            firstIndex.withAccess(reader -> {
                for (int attempt = 0; attempt < 20; attempt++) {
                    ObjectId previous = reader.findRef(MAIN).orElse(null);
                    ObjectId firstTarget = new ObjectId(String.format("%040x", attempt * 2 + 1));
                    ObjectId secondTarget = new ObjectId(String.format("%040x", attempt * 2 + 2));
                    firstIndex.withAccess(List.of(update(MAIN, previous, firstTarget),
                            update(OTHER, previous, firstTarget)), first -> {
                        firstIndex.withAccess(List.of(update(MAIN, previous, secondTarget),
                                update(OTHER, previous, secondTarget)), second -> {
                            CyclicBarrier start = new CyclicBarrier(2);
                            Future<Boolean> left = executor.submit(() -> applyAtBarrier(first, start));
                            Future<Boolean> right = executor.submit(() -> applyAtBarrier(second, start));
                            boolean firstWon = left.get(10, TimeUnit.SECONDS);
                            assertThat(right.get(10, TimeUnit.SECONDS)).isEqualTo(!firstWon);
                            ObjectId winner = firstWon ? firstTarget : secondTarget;
                            assertThat(reader.snapshotRefs(new RefSelection.All()).refs())
                                    .containsExactlyInAnyOrderEntriesOf(Map.of(MAIN, winner, OTHER, winner));
                            return null;
                        });
                        return null;
                    });
                }
                return null;
            });
        }
    }

    private static boolean applyAtBarrier(GitIndexAccess access, CyclicBarrier start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            access.apply();
            return true;
        } catch (GitRefConflictException expected) {
            return false;
        }
    }

    @Test
    void concurrentUnrelatedRefChangesBothApplyWithoutLosingEither() throws Exception {
        try (GitIndexApi index = new LocalGitIndex(directory);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            index.withAccess(List.of(update(MAIN, null, FIRST)), first -> {
                index.withAccess(List.of(update(OTHER, null, SECOND)), second -> {
                    CyclicBarrier start = new CyclicBarrier(2);
                    Future<Boolean> left = executor.submit(() -> applyAtBarrier(first, start));
                    Future<Boolean> right = executor.submit(() -> applyAtBarrier(second, start));
                    assertThat(left.get(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(right.get(10, TimeUnit.SECONDS)).isTrue();
                    return null;
                });
                return null;
            });
            assertRefs(index, Map.of(MAIN, FIRST, OTHER, SECOND));
        }
    }

    @Test
    void conflictDescriptionShowsTheRequestAndActualState() {
        GitRefConflictException conflict = new GitRefConflictException(
                update(MAIN, FIRST, null), Optional.empty());
        assertThat(conflict.toString()).isEqualTo("GitRefConflictException: Ref refs/heads/main: expected="
                + FIRST.toHex() + ", actual=<absent>, requested=<delete>");
        GitRefConflictException creation = new GitRefConflictException(
                update(MAIN, null, SECOND), Optional.of(FIRST));
        assertThat(creation.toString()).isEqualTo("GitRefConflictException: Ref refs/heads/main: expected=<absent>"
                + ", actual=" + FIRST.toHex() + ", requested=" + SECOND.toHex());
    }

    private static void publish(GitIndexApi index, RefId ref, ObjectId old, ObjectId next) throws IOException {
        index.withAccess(List.of(update(ref, old, next)), access -> {
            access.apply();
            return null;
        });
    }

    private static void stage(GitIndexAccess access, RefId ref, ObjectId old, ObjectId next) {
        assertThat(access.updateRefs(List.of(update(ref, old, next)), true).getFirst().status()).isEqualTo(APPLIED);
    }

    private static RefUpdate update(RefId ref, ObjectId old, ObjectId next) {
        return new RefUpdate(ref, Optional.ofNullable(old), Optional.ofNullable(next));
    }

    private static void assertRefs(GitIndexApi index, Map<RefId, ObjectId> expected) throws IOException {
        index.withAccess(access -> {
            assertThat(access.snapshotRefs(new RefSelection.All()).refs()).containsExactlyInAnyOrderEntriesOf(expected);
            return null;
        });
    }
}
