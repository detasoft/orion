package pro.deta.orion.git.client;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

public final class GitUploadPackClient {
    private final GitClientTransport transport;

    public GitUploadPackClient(GitClientTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public GitClientResult<GitRemoteAdvertisement> discover(
            URI remoteUri,
            GitClientOptions options) {
        return GitBlockingClientExecutor.execute(
                transport,
                GitClientService.UPLOAD_PACK,
                remoteUri,
                options,
                session -> new GitBlockingClientWire(session)
                        .readAdvertisement());
    }

    public GitClientResult<GitUploadPackResult> fetch(
            URI remoteUri,
            GitClientOptions options,
            GitUploadPackRequest request) {
        Objects.requireNonNull(request, "request");
        return GitBlockingClientExecutor.execute(
                transport,
                GitClientService.UPLOAD_PACK,
                remoteUri,
                options,
                session -> fetch(session, options, request));
    }

    private static GitUploadPackResult fetch(
            GitClientTransportSession session,
            GitClientOptions options,
            GitUploadPackRequest request)
            throws GitClientProtocolException {
        GitBlockingClientWire wire = new GitBlockingClientWire(session);
        GitRemoteAdvertisement advertisement;
        try {
            advertisement = wire.readAdvertisement();
        } catch (IOException error) {
            throw transportFailure(
                    GitClientFailure.Phase.ADVERTISEMENT,
                    "Failed to read upload-pack advertisement",
                    error);
        }
        try {
            wire.writeUploadRequest(request, advertisement);
        } catch (IOException error) {
            throw transportFailure(
                    GitClientFailure.Phase.NEGOTIATION,
                    "Failed to write upload-pack request",
                    error);
        }
        long packBytes;
        try {
            packBytes = wire.readUploadPack(
                    request, options.maximumPackBytes());
        } catch (IOException error) {
            throw transportFailure(
                    GitClientFailure.Phase.PACK_TRANSFER,
                    "Upload-pack transfer failed",
                    error);
        }
        return new GitUploadPackResult(advertisement, packBytes);
    }

    static GitClientProtocolException transportFailure(
            GitClientFailure.Phase phase,
            String message,
            IOException error) {
        return new GitClientProtocolException(new GitClientFailure(
                GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                phase,
                true,
                message,
                error));
    }
}
