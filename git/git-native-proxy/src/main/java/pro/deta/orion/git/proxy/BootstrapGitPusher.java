package pro.deta.orion.git.proxy;

import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import java.util.List;

@FunctionalInterface
interface BootstrapGitPusher {
    List<Boolean> push(
            BootstrapGitLocation location,
            GitClientTransport transport,
            NativeGitRepository repository,
            List<LooseRefStore.Update> updates,
            boolean atomic) throws Exception;
}
