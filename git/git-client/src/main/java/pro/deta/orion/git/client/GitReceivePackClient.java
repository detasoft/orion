package pro.deta.orion.git.client;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

public final class GitReceivePackClient {
    private final GitClientTransport transport;

    public GitReceivePackClient(GitClientTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public GitClientResult<GitRemoteAdvertisement> discover(
            URI remoteUri,
            GitClientOptions options) {
        return GitBlockingClientExecutor.execute(
                transport,
                GitClientService.RECEIVE_PACK,
                remoteUri,
                options,
                session -> new GitBlockingClientWire(session)
                        .readAdvertisement());
    }

    public GitClientResult<GitReceivePackResult> push(
            URI remoteUri,
            GitClientOptions options,
            GitReceivePackRequest request) {
        Objects.requireNonNull(request, "request");
        return GitBlockingClientExecutor.execute(
                transport,
                GitClientService.RECEIVE_PACK,
                remoteUri,
                options,
                session -> push(session, options, request));
    }

    private static GitReceivePackResult push(
            GitClientTransportSession session,
            GitClientOptions options,
            GitReceivePackRequest request)
            throws GitClientProtocolException {
        GitBlockingClientWire wire = new GitBlockingClientWire(session);
        GitRemoteAdvertisement advertisement;
        try {
            advertisement = wire.readAdvertisement();
        } catch (IOException error) {
            throw GitUploadPackClient.transportFailure(
                    GitClientFailure.Phase.ADVERTISEMENT,
                    "Failed to read receive-pack advertisement",
                    error);
        }
        try {
            wire.writeReceiveRequest(
                    request,
                    advertisement,
                    session.output(),
                    options.maximumPackBytes());
        } catch (IOException error) {
            throw GitUploadPackClient.transportFailure(
                    GitClientFailure.Phase.PACK_TRANSFER,
                    "Failed to write receive-pack request",
                    error);
        }
        try {
            return wire.readReceiveStatus(advertisement);
        } catch (IOException error) {
            throw GitUploadPackClient.transportFailure(
                    GitClientFailure.Phase.REPORT_STATUS,
                    "Failed to read receive-pack status",
                    error);
        }
    }
}
