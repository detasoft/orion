package pro.deta.orion.auth.check.resource;

import pro.deta.orion.auth.check.RootResource;

import java.net.SocketAddress;

/**
 * Transport-level resource for checks that depend on where a client connection comes from.
 */
public record ClientConnectionResource(SocketAddress remoteAddress) implements RootResource {
    public static ClientConnectionResource of(SocketAddress remoteAddress) {
        return new ClientConnectionResource(remoteAddress);
    }

    @Override
    public String describe() {
        return "client connection from " + remoteAddress;
    }
}
