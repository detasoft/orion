package pro.deta.orion.git.client;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Selects a native remote transport from the repository URI scheme.
 */
public final class GitRemoteClientTransport implements GitClientTransport {
    private final GitClientTransport file;
    private final GitClientTransport tcp;
    private final GitClientTransport ssh;
    private final GitClientTransport smartHttp;

    public GitRemoteClientTransport(
            GitClientTransport ssh,
            GitClientTransport smartHttp) {
        this(new GitFileClientTransport(), new GitTcpClientTransport(), ssh, smartHttp);
    }

    public GitRemoteClientTransport(
            GitClientTransport tcp,
            GitClientTransport ssh,
            GitClientTransport smartHttp) {
        this(new GitFileClientTransport(), tcp, ssh, smartHttp);
    }

    public GitRemoteClientTransport(
            GitClientTransport file,
            GitClientTransport tcp,
            GitClientTransport ssh,
            GitClientTransport smartHttp) {
        this.file = Objects.requireNonNull(file, "file");
        this.tcp = Objects.requireNonNull(tcp, "tcp");
        this.ssh = Objects.requireNonNull(ssh, "ssh");
        this.smartHttp = Objects.requireNonNull(smartHttp, "smartHttp");
    }

    @Override
    public GitClientTransportSession open(
            GitClientService service,
            URI remoteUri,
            GitClientOptions options) throws GitClientTransportException {
        Objects.requireNonNull(remoteUri, "remoteUri");
        String scheme = remoteUri.getScheme();
        if (scheme == null) {
            throw unsupported();
        }
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "file" -> file.open(service, remoteUri, options);
            case "git" -> tcp.open(service, remoteUri, options);
            case "ssh" -> ssh.open(service, remoteUri, options);
            case "http", "https" -> smartHttp.open(service, remoteUri, options);
            default -> throw unsupported();
        };
    }

    private static GitClientTransportException unsupported() {
        return new GitClientTransportException(
                GitClientFailure.Kind.PROTOCOL_UNSUPPORTED,
                false,
                "Remote Git URI uses an unsupported transport scheme");
    }
}
