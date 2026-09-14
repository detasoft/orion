package pro.deta.orion.git.nativestorage.receive;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.NativeObjectClosure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Applies receive policy to validated objects and conditional ref commands for both local and wire writes.
 * The publisher retains ownership of local or upstream publication and the final old-id comparison.
 */
public final class NativeGitReceivePack {
    private static final String ZERO = "0".repeat(40);

    private NativeGitReceivePack() {
    }

    public static List<ReceivePackStatus> complete(
            String repositoryName,
            NativeGitRepository repository,
            LooseObjectStore quarantine,
            List<LooseRefStore.Update> updates,
            boolean atomic,
            GitNativeRepositoryAccessHook accessHook,
            Function<List<LooseRefStore.Update>, List<RefUpdateResult>> publisher) {
        Objects.requireNonNull(repositoryName, "repositoryName");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(quarantine, "quarantine");
        updates = List.copyOf(updates);
        Objects.requireNonNull(accessHook, "accessHook");
        Objects.requireNonNull(publisher, "publisher");
        List<ReceivePackStatus> statuses = new ArrayList<>(updates.size());
        List<LooseRefStore.Update> validUpdates = new ArrayList<>();
        List<Integer> validIndexes = new ArrayList<>();
        boolean commandFailure = false;
        NativeObjectClosure closure = new NativeObjectClosure(id ->
                quarantine.read(id).or(() -> repository.readObject(id)));
        for (int index = 0; index < updates.size(); index++) {
            LooseRefStore.Update update = updates.get(index);
            if (!ZERO.equals(update.newId())
                    && !repository.hasCompleteObjectClosure(GitObjectId.of(update.newId()), quarantine)) {
                statuses.add(new ReceivePackStatus(update.refName(), false, "missing-necessary-objects"));
                commandFailure = true;
                continue;
            }
            boolean force = !ZERO.equals(update.expectedOldId()) && !ZERO.equals(update.newId())
                    && !update.expectedOldId().equals(update.newId())
                    && !closure.isAncestor(GitObjectId.of(update.expectedOldId()), GitObjectId.of(update.newId()));
            try {
                accessHook.beforeUpdate(repositoryName, update.refName(), force);
            } catch (GitNativeRepositoryAccessHook.AccessDeniedException error) {
                statuses.add(new ReceivePackStatus(update.refName(), false, "ACCESS_DENIED"));
                commandFailure = true;
                continue;
            }
            statuses.add(null);
            validIndexes.add(index);
            validUpdates.add(update);
        }
        if (atomic && commandFailure) {
            for (int index : validIndexes) {
                statuses.set(index, new ReceivePackStatus(updates.get(index).refName(), false, "atomic-push-failure"));
            }
            return List.copyOf(statuses);
        }
        List<RefUpdateResult> results = publisher.apply(List.copyOf(validUpdates));
        if (results.size() != validUpdates.size()) {
            throw new IllegalStateException("Ref publication result count does not match commands");
        }
        boolean atomicRefFailure = atomic && results.contains(RefUpdateResult.STALE);
        for (int resultIndex = 0; resultIndex < results.size(); resultIndex++) {
            int commandIndex = validIndexes.get(resultIndex);
            RefUpdateResult result = results.get(resultIndex);
            statuses.set(commandIndex, new ReceivePackStatus(
                    updates.get(commandIndex).refName(),
                    !atomicRefFailure && result != RefUpdateResult.STALE,
                    result == RefUpdateResult.STALE ? "stale" : atomicRefFailure ? "atomic-push-failure" : ""));
        }
        return List.copyOf(statuses);
    }
}
