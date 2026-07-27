package pro.deta.orion.git.client;

import java.net.URI;

public interface GitProtocolTransport {
    GitProtocolSession open(
            GitProtocolService service,
            URI remoteUri,
            GitProtocolTransportOptions options) throws GitProtocolTransportException;
}
