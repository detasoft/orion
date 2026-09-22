package pro.deta.orion.git.client;

import java.net.URI;
import java.util.Objects;

/**
 * Supported Git transport schemes, resolved from a URI without regard to letter case.
 */
public enum GitTransportScheme {
    FILE, GIT, SSH, HTTP, HTTPS;

    public static GitTransportScheme from(URI uri) throws GitClientTransportException {
        String value = Objects.requireNonNull(uri, "uri").getScheme();
        for (GitTransportScheme scheme : values()) {
            if (scheme.name().equalsIgnoreCase(value)) {
                return scheme;
            }
        }
        throw new GitClientTransportException(
                GitClientFailure.Kind.PROTOCOL_UNSUPPORTED, false,
                value == null ? "Remote Git URI requires a transport scheme"
                        : "Remote Git URI uses an unsupported transport scheme");
    }
}
