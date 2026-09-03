package pro.deta.orion.git.proxy;

import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.nativestorage.NativeGitRepository;

@FunctionalInterface
interface BootstrapGitFetcher {
    void fetch(
            BootstrapGitLocation location,
            GitClientTransport transport,
            NativeGitRepository repository) throws Exception;
}
