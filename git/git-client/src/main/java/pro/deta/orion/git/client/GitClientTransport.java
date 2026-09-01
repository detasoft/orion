package pro.deta.orion.git.client;

import java.net.URI;

public interface GitClientTransport {
    GitClientTransportSession open(
            GitClientService service,
            URI remoteUri,
            GitClientOptions options) throws GitClientTransportException;
}
