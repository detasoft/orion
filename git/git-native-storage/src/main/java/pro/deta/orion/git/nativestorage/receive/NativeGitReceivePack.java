package pro.deta.orion.git.nativestorage.receive;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.upload.NativeObjectClosure;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

public final class NativeGitReceivePack {
    private NativeGitReceivePack() {}

    public static List<RefUpdateResult> complete(String repositoryName, NativeGitRepository repository,
            List<RefUpdate> updates, boolean atomic, GitNativeRepositoryAccessHook accessHook,
            Function<List<RefUpdate>, List<RefUpdateResult>> publisher) {
        Objects.requireNonNull(repositoryName, "repositoryName");
        Objects.requireNonNull(repository, "repository");
        updates = List.copyOf(updates);
        Objects.requireNonNull(accessHook, "accessHook");
        Objects.requireNonNull(publisher, "publisher");
        List<RefUpdateResult> results = new ArrayList<>(updates.size());
        List<RefUpdate> valid = new ArrayList<>();
        NativeObjectClosure closure = new NativeObjectClosure(repository::readObject);
        for (RefUpdate update : updates) {
            if (update.newId().isPresent() && !repository.hasCompleteObjectClosure(
                    GitObjectId.of(update.newId().orElseThrow().toHex()))) {
                results.add(new RefUpdateResult(update, OBJECT_NOT_FOUND, Optional.of("missing necessary objects")));
                continue;
            }
            boolean force = update.expectedOld().isPresent() && update.newId().isPresent()
                    && !update.expectedOld().equals(update.newId())
                    && !closure.isAncestor(GitObjectId.of(update.expectedOld().orElseThrow().toHex()),
                            GitObjectId.of(update.newId().orElseThrow().toHex()));
            try {
                accessHook.beforeUpdate(repositoryName, update.ref().value(), force);
                valid.add(update);
                results.add(new RefUpdateResult(update, APPLIED, Optional.empty()));
            } catch (GitNativeRepositoryAccessHook.AccessDeniedException failure) {
                results.add(new RefUpdateResult(update, REJECTED, Optional.of("ACCESS_DENIED")));
            }
        }
        if (atomic && valid.size() != updates.size()) {
            for (int index = 0; index < results.size(); index++) {
                if (results.get(index).status() == APPLIED) {
                    results.set(index, new RefUpdateResult(updates.get(index), ATOMIC_ABORTED, Optional.empty()));
                }
            }
            return List.copyOf(results);
        }
        List<RefUpdateResult> published = publisher.apply(List.copyOf(valid));
        if (published.size() != valid.size()) {
            throw new IllegalStateException("Ref publication result count does not match commands");
        }
        int publishedIndex = 0;
        for (int index = 0; index < results.size(); index++) {
            if (results.get(index).status() == APPLIED) {
                results.set(index, published.get(publishedIndex++));
            }
        }
        return List.copyOf(results);
    }
}
