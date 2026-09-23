package pro.deta.orion.git.proxy;

import pro.deta.orion.git.client.GitClientFailure;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncStatus;

/** Keeps a stable proxy message and category, with the original cause available to error handlers. */
final class BootstrapGitProxyException extends IllegalStateException {
    private final SyncStatus status;

    BootstrapGitProxyException(String stage) {
        this(stage, SyncStatus.UNAVAILABLE);
    }

    BootstrapGitProxyException(String stage, GitClientFailure failure) {
        this(stage, switch (failure.kind()) {
            case AUTHENTICATION_FAILED -> SyncStatus.AUTHENTICATION_FAILED;
            default -> SyncStatus.UNAVAILABLE;
        });
        initCause(failure.cause());
    }

    BootstrapGitProxyException(String stage, SyncStatus status) {
        super("Remote Git bootstrap failed during " + stage);
        this.status = status;
    }

    SyncStatus status() {
        return status;
    }
}
