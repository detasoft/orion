package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;

import java.util.List;

final class NativeFetchedRefPublisher {

    private NativeFetchedRefPublisher() {
    }

    static void publish(
            NativeGitRepository repository,
            RefUpdate update) {
        try {
            if (update.newId().isPresent()
                    && !repository.hasCompleteObjectClosure(
                            update.newId().orElseThrow())) {
                throw new BootstrapGitProxyException("complete object validation");
            }
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BootstrapGitProxyException("complete object validation");
        }
        List<RefUpdateResult> results = repository.publishRefs(
                List.of(update),
                true);
        if (results.getFirst().status() != RefUpdateResult.Status.APPLIED) {
            throw new BootstrapGitProxyException("local ref publication",
                    results.getFirst().status() == RefUpdateResult.Status.EXPECTED_OLD_MISMATCH
                            ? ProxyAwareNativeGitRepositoryProvider.SyncStatus.CONFLICT
                            : ProxyAwareNativeGitRepositoryProvider.SyncStatus.UNAVAILABLE);
        }
    }
}
