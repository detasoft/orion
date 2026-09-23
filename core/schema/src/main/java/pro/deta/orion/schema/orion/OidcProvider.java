package pro.deta.orion.schema.orion;

import java.net.URI;
import java.util.Objects;

/** OIDC client configuration owned by an organization; secret names refer to that organization's secrets. */
public record OidcProvider(String id, URI issuer, String clientId, String secret) {
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
        secret = IdentifierRules.requireCanonical(secret, "OIDC secret reference");
    }
}
