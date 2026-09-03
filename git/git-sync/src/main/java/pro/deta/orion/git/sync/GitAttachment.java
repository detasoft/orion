package pro.deta.orion.git.sync;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore.Update;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class GitAttachment {
    private static final String NULL_ID = "0".repeat(40);
    private static final String HEAD_PREFIX = "refs/heads/";

    private final NativeGitRepository repository;
    private final GitRemoteGateway gateway;
    private final GitCommitRelationships relationships;
    private final GitAttachPlanner planner = new GitAttachPlanner();

    public GitAttachment(
            NativeGitRepository repository,
            GitRemoteGateway gateway,
            GitCommitRelationships relationships) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.relationships = Objects.requireNonNull(relationships, "relationships");
    }

    public GitAttachmentResult attach() throws GitRemoteException {
        Map<String, String> upstreamHeads = gateway.fetchHeads(repository).heads();
        while (true) {
            GitAttachPlan plan = planner.plan(
                    liveHeads(),
                    upstreamHeads,
                    relationships);
            if (!plan.compatible()) {
                return GitAttachmentResult.conflicted(conflicts(plan));
            }
            if (!publishLocalChanges(plan)) {
                continue;
            }
            return pushLocalChanges(plan);
        }
    }

    private Map<String, String> liveHeads() {
        Map<String, String> heads = new TreeMap<>();
        for (Map.Entry<String, String> entry : repository.refs().entrySet()) {
            if (entry.getKey().startsWith(HEAD_PREFIX)) {
                heads.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(heads);
    }

    private boolean publishLocalChanges(GitAttachPlan plan) {
        List<Update> updates = new ArrayList<>();
        for (GitBranchPlan branch : plan.branches()) {
            String newObjectId = branch.action() == GitBranchAction.CREATE_LOCAL
                    || branch.action() == GitBranchAction.FAST_FORWARD_LOCAL
                    ? branch.upstreamObjectId().orElseThrow()
                    : branch.localObjectId().orElseThrow();
            updates.add(new Update(
                    branch.refName(),
                    branch.localObjectId().orElse(NULL_ID),
                    newObjectId));
        }
        if (updates.isEmpty()) {
            return true;
        }
        List<RefUpdateResult> results = repository.publishObjectsAndRefs(
                new LooseObjectStore(),
                updates);
        return !results.contains(RefUpdateResult.STALE);
    }

    private GitAttachmentResult pushLocalChanges(GitAttachPlan plan)
            throws GitRemoteException {
        for (GitBranchPlan branch : plan.branches()) {
            if (branch.action() != GitBranchAction.PUSH_UPSTREAM) {
                continue;
            }
            String desired = branch.localObjectId().orElseThrow();
            GitPushOutcome outcome = gateway.pushHead(
                    repository,
                    branch.refName(),
                    branch.upstreamObjectId().orElse(null),
                    desired);
            if (outcome.status() == GitPushOutcome.Status.APPLIED
                    || outcome.status() == GitPushOutcome.Status.ALREADY_CURRENT) {
                continue;
            }
            if (outcome.status() == GitPushOutcome.Status.REJECTED) {
                throw GitRemoteException.local("push rejection", false, null);
            }
            Optional<String> observed = outcome.observedObjectId();
            Optional<String> mergeBase = observed.flatMap(value ->
                    relationships.mergeBase(desired, value));
            return GitAttachmentResult.conflicted(List.of(new GitSyncConflict(
                    branch.refName(),
                    Optional.of(desired),
                    observed,
                    mergeBase)));
        }
        return GitAttachmentResult.attached();
    }

    private static List<GitSyncConflict> conflicts(GitAttachPlan plan) {
        List<GitSyncConflict> conflicts = new ArrayList<>();
        for (GitBranchPlan branch : plan.conflicts()) {
            conflicts.add(GitSyncConflict.from(branch));
        }
        return List.copyOf(conflicts);
    }
}
