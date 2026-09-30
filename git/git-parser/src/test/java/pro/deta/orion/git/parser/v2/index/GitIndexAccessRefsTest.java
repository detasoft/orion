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
        try (GitIndexAccess access = index.createAccess(List.of(update(MAIN, FIRST, SECOND)))) {
            access.apply();
        }
        assertRefs(index, Map.of(MAIN, SECOND));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refsStayPrivateUntilApplyAndDiscardLeavesThemUnchanged(boolean local) throws Exception {
        GitIndexApi index = index(local);
        try (GitIndexAccess access = index.createAccess(Set.of(MAIN))) {
            stage(access, MAIN, null, FIRST);
            assertThat(access.snapshotRefs().refs()).containsEntry(MAIN, FIRST);
            assertRefs(index, Map.of());
            access.discard();
            access.discard();
        }
        assertRefs(index, Map.of());
        publish(index, MAIN, null, FIRST);
        assertRefs(index, Map.of(MAIN, FIRST));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUndeclaredRefsBeforeChangingAnyPendingState(boolean local) throws Exception {
        GitIndexApi index = index(local);
        try (GitIndexAccess access = index.createAccess()) {
            assertThatThrownBy(() -> stage(access, MAIN, null, FIRST))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared");
        }
        try (GitIndexAccess access = index.createAccess(Set.of(MAIN))) {
            assertThatThrownBy(() -> access.updateRefs(List.of(
                    update(MAIN, null, FIRST), update(OTHER, null, FIRST)), true))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared");
            access.apply();
        }
        assertRefs(index, Map.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsCapturedValueAndRejectsConcurrentChangeAtApply(boolean local) throws Exception {
        GitIndexApi index = index(local);
        publish(index, MAIN, null, FIRST);
        try (GitIndexAccess access = index.createAccess(Set.of(MAIN, OTHER))) {
            publish(index, MAIN, FIRST, SECOND);
            assertThat(access.snapshotRefs().refs()).containsEntry(MAIN, FIRST);
            access.updateRefs(List.of(update(MAIN, FIRST, null), update(OTHER, null, FIRST)), true);
            assertThatThrownBy(access::apply)
                    .isInstanceOfSatisfying(GitRefConflictException.class, conflict -> {
                        assertThat(conflict.update()).isEqualTo(update(MAIN, FIRST, null));
                        assertThat(conflict.actual()).contains(SECOND);
                    });
            assertThatThrownBy(access::snapshotRefs).isInstanceOf(IOException.class);
        }
        assertRefs(index, Map.of(MAIN, SECOND));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distinguishesExpectedAbsenceFromUndeclaredAndDetectsConcurrentCreation(boolean local) throws Exception {
        GitIndexApi index = index(local);
        try (GitIndexAccess first = index.createAccess(List.of(update(MAIN, null, FIRST)));
             GitIndexAccess second = index.createAccess(List.of(update(MAIN, null, SECOND)))) {
            first.apply();
            assertThatThrownBy(second::apply).isInstanceOf(GitRefConflictException.class);
        }
        assertRefs(index, Map.of(MAIN, FIRST));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyChecksModifiedRefsAndPreservesConcurrentUnrelatedUpdates(boolean local) throws Exception {
        GitIndexApi index = index(local);
        try (GitIndexAccess access = index.createAccess(Set.of(MAIN, OTHER))) {
            publish(index, OTHER, null, SECOND);
            stage(access, MAIN, null, FIRST);
            access.apply();
        }
        assertRefs(index, Map.of(MAIN, FIRST, OTHER, SECOND));
        publish(index, MAIN, FIRST, null);
        assertRefs(index, Map.of(OTHER, SECOND));
    }

    private GitIndexApi index(boolean local) throws IOException {
        return local ? new LocalGitIndex(directory) : new InMemoryIndex();
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
        try (GitIndexAccess access = index.createAccess(List.of(update(ref, old, next)))) {
            access.apply();
        }
    }

    private static void stage(GitIndexAccess access, RefId ref, ObjectId old, ObjectId next) {
        assertThat(access.updateRefs(List.of(update(ref, old, next)), true).getFirst().status()).isEqualTo(APPLIED);
    }

    private static RefUpdate update(RefId ref, ObjectId old, ObjectId next) {
        return new RefUpdate(ref, Optional.ofNullable(old), Optional.ofNullable(next));
    }

    private static void assertRefs(GitIndexApi index, Map<RefId, ObjectId> expected) throws IOException {
        try (GitIndexAccess access = index.createAccess()) {
            assertThat(access.snapshotRefs().refs()).containsExactlyInAnyOrderEntriesOf(expected);
        }
    }
}
