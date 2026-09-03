package pro.deta.orion.git.sync;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore.Update;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GitAttachmentTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String A = "1".repeat(40);
    private static final String B = "2".repeat(40);
    private static final String C = "3".repeat(40);
    private static final String D = "4".repeat(40);
    private static final String E = "5".repeat(40);
    private static final String F = "6".repeat(40);

    @Test
    void importsEveryUpstreamBranchIntoAnEmptyRepository() throws Exception {
        NativeGitRepository repository = repository();
        FakeGateway gateway = new FakeGateway(Map.of(
                head("main"), A,
                head("release"), B));

        GitAttachmentResult result = attachment(repository, gateway, new FakeRelationships()).attach();

        assertThat(result.active()).isTrue();
        assertThat(result.conflicts()).isEmpty();
        assertThat(repository.refs()).containsAllEntriesOf(Map.of(
                head("main"), A,
                head("release"), B,
                tracking("main"), A,
                tracking("release"), B));
        assertThat(gateway.pushes).isEmpty();
    }

    @Test
    void reconcilesFastForwardsAndOrionAheadBranches() throws Exception {
        NativeGitRepository repository = repository();
        repository.updateRef(head("main"), NULL_ID, A);
        repository.updateRef(head("release"), NULL_ID, D);
        FakeGateway gateway = new FakeGateway(Map.of(
                head("feature"), E,
                head("main"), B,
                head("release"), C));
        FakeRelationships relationships = new FakeRelationships();
        relationships.ancestor(A, B);
        relationships.ancestor(C, D);

        GitAttachmentResult result = attachment(repository, gateway, relationships).attach();

        assertThat(result.active()).isTrue();
        assertThat(repository.refs()).containsAllEntriesOf(Map.of(
                head("feature"), E,
                head("main"), B,
                head("release"), D,
                tracking("feature"), E,
                tracking("main"), B,
                tracking("release"), D));
        assertThat(gateway.heads).containsAllEntriesOf(Map.of(
                head("feature"), E,
                head("main"), B,
                head("release"), D));
        assertThat(gateway.pushes).containsExactly(head("release"));
    }

    @Test
    void preservesLiveAndRemoteHeadsWhenAnyBranchDiverges() throws Exception {
        NativeGitRepository repository = repository();
        repository.updateRef(head("main"), NULL_ID, A);
        repository.updateRef(head("release"), NULL_ID, C);
        FakeGateway gateway = new FakeGateway(Map.of(
                head("main"), B,
                head("release"), D));
        FakeRelationships relationships = new FakeRelationships();
        relationships.ancestor(C, D);
        relationships.mergeBase(A, B, E);

        GitAttachmentResult result = attachment(repository, gateway, relationships).attach();

        assertThat(result.active()).isFalse();
        assertThat(result.conflicts()).containsExactly(new GitSyncConflict(
                head("main"),
                Optional.of(A),
                Optional.of(B),
                Optional.of(E)));
        assertThat(repository.refs()).containsAllEntriesOf(Map.of(
                head("main"), A,
                head("release"), C,
                tracking("main"), B,
                tracking("release"), D));
        assertThat(gateway.heads).containsAllEntriesOf(Map.of(
                head("main"), B,
                head("release"), D));
        assertThat(gateway.pushes).isEmpty();
    }

    @Test
    void recomputesTheWholePlanAfterAStaleAtomicPublication() throws Exception {
        RacingRepository repository = new RacingRepository(head("main"), A, F);
        repository.updateRef(head("main"), NULL_ID, A);
        repository.updateRef(head("release"), NULL_ID, C);
        FakeGateway gateway = new FakeGateway(Map.of(
                head("main"), B,
                head("release"), D));
        FakeRelationships relationships = new FakeRelationships();
        relationships.ancestor(A, B);
        relationships.ancestor(C, D);

        GitAttachmentResult result = attachment(repository, gateway, relationships).attach();

        assertThat(result.active()).isFalse();
        assertThat(result.conflicts()).extracting(GitSyncConflict::refName)
                .containsExactly(head("main"));
        assertThat(repository.livePublicationAttempts).isEqualTo(1);
        assertThat(repository.refs()).containsAllEntriesOf(Map.of(
                head("main"), F,
                head("release"), C,
                tracking("main"), B,
                tracking("release"), D));
        assertThat(gateway.pushes).isEmpty();
    }

    @Test
    void guardsUnchangedAndPushCandidatesBeforeApplyingThePlan() throws Exception {
        RacingRepository repository = new RacingRepository(head("release"), C, F);
        repository.updateRef(head("main"), NULL_ID, B);
        repository.updateRef(head("release"), NULL_ID, C);
        FakeGateway gateway = new FakeGateway(Map.of(
                head("main"), A,
                head("release"), C));
        FakeRelationships relationships = new FakeRelationships();
        relationships.ancestor(A, B);

        GitAttachmentResult result = attachment(repository, gateway, relationships).attach();

        assertThat(result.active()).isFalse();
        assertThat(result.conflicts()).extracting(GitSyncConflict::refName)
                .containsExactly(head("release"));
        assertThat(repository.livePublicationAttempts).isEqualTo(1);
        assertThat(repository.refs()).containsAllEntriesOf(Map.of(
                head("main"), B,
                head("release"), F));
        assertThat(gateway.heads).containsAllEntriesOf(Map.of(
                head("main"), A,
                head("release"), C));
        assertThat(gateway.pushes).isEmpty();
    }

    private static GitAttachment attachment(
            NativeGitRepository repository,
            GitRemoteGateway gateway,
            GitCommitRelationships relationships) {
        return new GitAttachment(repository, gateway, relationships);
    }

    private static NativeGitRepository repository() {
        return new NativeGitRepository(
                "project",
                new LooseRefStore(),
                new LooseObjectStore(),
                head("main"));
    }

    private static String head(String branch) {
        return "refs/heads/" + branch;
    }

    private static String tracking(String branch) {
        return "refs/remotes/upstream/" + branch;
    }

    private static final class FakeGateway implements GitRemoteGateway {
        private final Map<String, String> heads = new HashMap<>();
        private final List<String> pushes = new ArrayList<>();

        private FakeGateway(Map<String, String> heads) {
            this.heads.putAll(heads);
        }

        @Override
        public GitFetchedHeads fetchHeads(NativeGitRepository repository) {
            Map<String, String> refs = repository.refs();
            for (Map.Entry<String, String> entry : heads.entrySet()) {
                String branch = entry.getKey().substring("refs/heads/".length());
                String trackingRef = tracking(branch);
                repository.updateRef(
                        trackingRef,
                        refs.getOrDefault(trackingRef, NULL_ID),
                        entry.getValue());
            }
            return new GitFetchedHeads(heads);
        }

        @Override
        public Map<String, String> listHeads() {
            return Map.copyOf(heads);
        }

        @Override
        public GitPushOutcome pushHead(
                NativeGitRepository repository,
                String refName,
                String expectedRemoteId,
                String desiredId) {
            String observed = heads.get(refName);
            if (desiredId.equals(observed)) {
                return new GitPushOutcome(
                        GitPushOutcome.Status.ALREADY_CURRENT,
                        Optional.of(observed));
            }
            if (!java.util.Objects.equals(expectedRemoteId, observed)) {
                return new GitPushOutcome(
                        GitPushOutcome.Status.REMOTE_CHANGED,
                        Optional.ofNullable(observed));
            }
            heads.put(refName, desiredId);
            String trackingRef = tracking(refName.substring("refs/heads/".length()));
            repository.updateRef(
                    trackingRef,
                    repository.refs().getOrDefault(trackingRef, NULL_ID),
                    desiredId);
            pushes.add(refName);
            return new GitPushOutcome(
                    GitPushOutcome.Status.APPLIED,
                    Optional.ofNullable(observed));
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeRelationships implements GitCommitRelationships {
        private final Map<Pair, Boolean> ancestors = new HashMap<>();
        private final Map<Pair, Optional<String>> mergeBases = new HashMap<>();

        void ancestor(String ancestor, String descendant) {
            ancestors.put(new Pair(ancestor, descendant), true);
        }

        void mergeBase(String first, String second, String mergeBase) {
            mergeBases.put(new Pair(first, second), Optional.of(mergeBase));
            mergeBases.put(new Pair(second, first), Optional.of(mergeBase));
        }

        @Override
        public boolean isAncestor(String ancestor, String descendant) {
            return ancestor.equals(descendant)
                    || ancestors.getOrDefault(new Pair(ancestor, descendant), false);
        }

        @Override
        public Optional<String> mergeBase(String first, String second) {
            return mergeBases.getOrDefault(new Pair(first, second), Optional.empty());
        }
    }

    private static final class RacingRepository extends NativeGitRepository {
        private final String racedRef;
        private final String expectedOldId;
        private final String concurrentId;
        private boolean racePending = true;
        private int livePublicationAttempts;

        private RacingRepository(
                String racedRef,
                String expectedOldId,
                String concurrentId) {
            super(
                    "project",
                    new LooseRefStore(),
                    new LooseObjectStore(),
                    head("main"));
            this.racedRef = racedRef;
            this.expectedOldId = expectedOldId;
            this.concurrentId = concurrentId;
        }

        @Override
        public List<RefUpdateResult> publishObjectsAndRefs(
                LooseObjectStore quarantinedObjects,
                List<Update> updates) {
            livePublicationAttempts++;
            if (racePending) {
                racePending = false;
                updateRef(racedRef, expectedOldId, concurrentId);
            }
            return super.publishObjectsAndRefs(quarantinedObjects, updates);
        }
    }

    private record Pair(String first, String second) {
    }
}
