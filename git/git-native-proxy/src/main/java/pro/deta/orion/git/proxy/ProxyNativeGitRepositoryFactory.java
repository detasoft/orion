package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;

/** Opens the proxy cache and creates a repository for the selected Git location. */
final class ProxyNativeGitRepositoryFactory {
    private ProxyNativeGitRepositoryFactory() {
    }

    static ProxyNativeGitRepository create(String name, BootstrapGitLocation location,
            NativeGitRepositoryProvider provider, NativeGitRepositoryBackend backend,
            BootstrapGitTransportFactory transportFactory,
            BootstrapGitFetcher fetcher, BootstrapGitPusher pusher) {
        NativeGitRepository cache = provider.openBacking(name, backend);
        return new ProxyNativeGitRepository(name, location, cache, transportFactory, fetcher, pusher);
    }
}
