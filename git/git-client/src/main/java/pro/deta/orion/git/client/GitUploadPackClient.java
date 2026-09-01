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
                GitUploadPackClient::discover);
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
                    request, advertisement, options.maximumPackBytes());
        } catch (IOException error) {
            throw transportFailure(
                    GitClientFailure.Phase.PACK_TRANSFER,
                    "Upload-pack transfer failed",
                    error);
        }
        return new GitUploadPackResult(advertisement, packBytes);
    }

    private static GitRemoteAdvertisement discover(
            GitClientTransportSession session) throws GitClientProtocolException {
        try {
            return new GitBlockingClientWire(session).readAdvertisement();
        } catch (IOException error) {
            throw transportFailure(
                    GitClientFailure.Phase.ADVERTISEMENT,
                    "Failed to read upload-pack advertisement",
                    error);
        }
    }

    static GitClientProtocolException transportFailure(
            GitClientFailure.Phase phase,
            String message,
            IOException error) {
        GitClientFailure.Kind kind = GitClientFailure.Kind.TRANSPORT_UNAVAILABLE;
        boolean retryable = true;
        if (error instanceof GitClientTransportException transportError) {
            kind = transportError.kind();
            retryable = transportError.retryable();
        }
        return new GitClientProtocolException(new GitClientFailure(
                kind,
                phase,
                retryable,
                message,
                error));
    }
}
