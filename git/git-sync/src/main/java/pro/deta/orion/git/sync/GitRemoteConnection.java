package pro.deta.orion.git.sync;

import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitUploadPackClient;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GitRemoteConnection implements AutoCloseable {
    private final URI uri;
    private final GitClientOptions options;
    private final GitUploadPackClient uploadPack;
    private final GitReceivePackClient receivePack;
    private final Runnable cleanup;
    private final AtomicBoolean closed = new AtomicBoolean();

    GitRemoteConnection(
            URI uri,
            GitClientOptions options,
            GitUploadPackClient uploadPack,
            GitReceivePackClient receivePack,
            Runnable cleanup) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.options = Objects.requireNonNull(options, "options");
        this.uploadPack = Objects.requireNonNull(uploadPack, "uploadPack");
        this.receivePack = Objects.requireNonNull(receivePack, "receivePack");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public URI uri() {
        return uri;
    }

    public GitClientOptions options() {
        return options;
    }

    public GitUploadPackClient uploadPack() {
        return uploadPack;
    }

    public GitReceivePackClient receivePack() {
        return receivePack;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanup.run();
        }
    }
}
