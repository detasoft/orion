package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.util.Result;

/** Opens the proxy cache and creates a repository for the selected Git location. */
final class ProxyNativeGitRepositoryFactory {
    private ProxyNativeGitRepositoryFactory() {
    }

    static ProxyNativeGitRepository create(String name, BootstrapGitLocation location,
            NativeGitRepositoryProvider backend, BootstrapGitTransportFactory transportFactory,
            BootstrapGitFetcher fetcher, BootstrapGitPusher pusher) {
        NativeGitRepository cache = openOrCreate(name, backend);
        return new ProxyNativeGitRepository(name, location, cache, transportFactory, fetcher, pusher);
    }

    static NativeGitRepository openOrCreate(String name, NativeGitRepositoryProvider backend) {
        if (backend.exists(name)) {
            return backend.find(name).valueOrFailure("Cannot open Git proxy");
        }
        Result<NativeGitRepository> created = backend.create(name);
        if (created instanceof Result.Failure<NativeGitRepository> failure
                && failure.code() == Result.FailureCode.FILE_ALREADY_EXISTS) {
            return backend.find(name).valueOrFailure("Cannot open Git proxy");
        }
        return created.valueOrFailure("Cannot create Git proxy");
    }
}
