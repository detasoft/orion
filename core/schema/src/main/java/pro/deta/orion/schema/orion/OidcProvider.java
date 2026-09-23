package pro.deta.orion.schema.orion;

import java.net.URI;
import java.util.Objects;

/** OIDC client configuration owned by an organization; secret names refer to that organization's secrets. */
public record OidcProvider(String id, URI issuer, String clientId, String secret,
        long idleTimeoutSeconds, long reauthenticationTimeoutSeconds) {
    public static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 48 * 60 * 60;
    public OidcProvider {
        id = IdentifierRules.requireCanonical(id, "OIDC provider id");
        Objects.requireNonNull(issuer, "OIDC issuer");
        if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null
                || issuer.getUserInfo() != null || issuer.getQuery() != null || issuer.getFragment() != null) {
            throw new IllegalArgumentException(
                    "OIDC issuer must be an HTTPS URL without credentials, query or fragment");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("OIDC client id must not be blank");
        }
        if (idleTimeoutSeconds <= 0 || idleTimeoutSeconds > Integer.MAX_VALUE
                || reauthenticationTimeoutSeconds < 0 || reauthenticationTimeoutSeconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("OIDC timeouts must be positive seconds; reauthentication may be zero");
        }
        secret = IdentifierRules.requireCanonical(secret, "OIDC secret reference");
    }
}
