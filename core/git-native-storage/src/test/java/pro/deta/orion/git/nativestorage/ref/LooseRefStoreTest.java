package pro.deta.orion.git.nativestorage.ref;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LooseRefStoreTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String SHA1_A = "a".repeat(40);
    private static final String SHA1_B = "b".repeat(40);
    private static final String SHA1_C = "c".repeat(40);

    private final LooseRefStore store = new LooseRefStore();

    @Test
    void readReturnsEmptyForAbsentRef() {
        assertThat(store.read("refs/heads/main")).isEmpty();
    }

    @Test
    void createRefWithNullOldId() {
        RefUpdateResult result = store.update("refs/heads/main", NULL_ID, SHA1_A);

        assertThat(result).isEqualTo(RefUpdateResult.CREATED);
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void createRefFailsIfRefAlreadyExists() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", NULL_ID, SHA1_B);

        assertThat(result).isEqualTo(RefUpdateResult.STALE);
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void updateRefWithMatchingOldId() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", SHA1_A, SHA1_B);

        assertThat(result).isEqualTo(RefUpdateResult.FAST_FORWARD);
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_B));
    }

    @Test
    void updateRefFailsWithStaleOldId() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", SHA1_B, SHA1_C);

        assertThat(result).isEqualTo(RefUpdateResult.STALE);
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void updateNonExistentRefWithNonNullOldIdIsStale() {
        RefUpdateResult result = store.update("refs/heads/main", SHA1_A, SHA1_B);

        assertThat(result).isEqualTo(RefUpdateResult.STALE);
        assertThat(store.read("refs/heads/main")).isEmpty();
    }

    @Test
    void snapshotReturnsCurrentRefs() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);
        store.update("refs/heads/feature", NULL_ID, SHA1_B);

        var snapshot = store.snapshot();

        assertThat(snapshot).containsEntry("refs/heads/main", SHA1_A);
        assertThat(snapshot).containsEntry("refs/heads/feature", SHA1_B);
    }

    @Test
    void snapshotIsImmutableCopy() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);
        var snapshot = store.snapshot();

        store.update("refs/heads/main", SHA1_A, SHA1_B);

        assertThat(snapshot).containsEntry("refs/heads/main", SHA1_A);
    }

    @Test
    void noOpWhenCreatingRefWithSameTargetThatAlreadyExists() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", NULL_ID, SHA1_A);

        assertThat(result).isEqualTo(RefUpdateResult.NO_OP);
    }

    @Test
    void noOpWhenUpdatingRefToSameTarget() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);

        RefUpdateResult result = store.update("refs/heads/main", SHA1_A, SHA1_A);

        assertThat(result).isEqualTo(RefUpdateResult.NO_OP);
    }

    @Test
    void batchPublishesObjectsBeforeMakingSuccessfulRefsVisible() {
        AtomicBoolean objectsPublished = new AtomicBoolean();

        List<RefUpdateResult> results = store.updateAll(
                List.of(new LooseRefStore.Update("refs/heads/main", NULL_ID, SHA1_A)),
                () -> {
                    assertThat(store.read("refs/heads/main")).isEmpty();
                    objectsPublished.set(true);
                });

        assertThat(results).containsExactly(RefUpdateResult.CREATED);
        assertThat(objectsPublished).isTrue();
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }

    @Test
    void batchDoesNotPublishObjectsWhenEveryUpdateIsStale() {
        store.update("refs/heads/main", NULL_ID, SHA1_A);
        AtomicBoolean objectsPublished = new AtomicBoolean();

        List<RefUpdateResult> results = store.updateAll(
                List.of(new LooseRefStore.Update("refs/heads/main", SHA1_B, SHA1_C)),
                () -> objectsPublished.set(true));

        assertThat(results).containsExactly(RefUpdateResult.STALE);
        assertThat(objectsPublished).isFalse();
        assertThat(store.read("refs/heads/main")).contains(GitObjectId.of(SHA1_A));
    }
}
