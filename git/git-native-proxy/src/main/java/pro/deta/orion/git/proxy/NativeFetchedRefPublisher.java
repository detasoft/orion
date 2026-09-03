package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;

import java.util.List;

final class NativeFetchedRefPublisher {
    private static final String NULL_ID = "0".repeat(40);

    private NativeFetchedRefPublisher() {
    }

    static void publish(
            NativeGitRepository repository,
            LooseObjectStore objects,
            LooseRefStore.Update update) {
        try {
            if (!NULL_ID.equals(update.newId())
                    && !repository.hasCompleteObjectClosure(
                            GitObjectId.of(update.newId()),
                            objects)) {
                throw new BootstrapGitProxyException("complete object validation");
            }
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BootstrapGitProxyException("complete object validation");
        }
        List<RefUpdateResult> results = repository.publishObjectsAndRefs(
                objects,
                List.of(update),
                true);
        if (!results.equals(List.of(RefUpdateResult.CREATED))
                && !results.equals(List.of(RefUpdateResult.FAST_FORWARD))
                && !results.equals(List.of(RefUpdateResult.NO_OP))) {
            throw new BootstrapGitProxyException("local ref publication");
        }
    }
}
