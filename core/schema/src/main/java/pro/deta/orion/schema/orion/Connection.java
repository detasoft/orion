package pro.deta.orion.schema.orion;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;

/** A named transport configuration owned by its enclosing system or organization. */
public sealed interface Connection {
    String name();

    default boolean referencesSecret(String id) {
        return switch (this) {
            case S3 s3 -> s3.secretKey().filter(id::equals).isPresent()
                    || s3.sessionToken().filter(id::equals).isPresent();
            case Ssh ssh -> ssh.secret().filter(id::equals).isPresent();
        };
    }

    record S3(String name, Optional<URI> endpoint, String region, boolean pathStyleAccess,
            Optional<String> accessKeyId, Optional<String> secretKey, Optional<String> sessionToken)
            implements Connection {
        public S3 {
            name = IdentifierRules.requireCanonical(name, "connection name");
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            if (endpoint.isPresent()) {
                URI uri = endpoint.orElseThrow();
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException("S3 endpoint must be an HTTP(S) URL without credentials");
                }
            }
            region = region == null ? "us-east-1" : region;
            if (!region.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid S3 region");
            accessKeyId = Objects.requireNonNull(accessKeyId, "access key ID");
            secretKey = secretReference(secretKey);
            sessionToken = secretReference(sessionToken);
            if (accessKeyId.isPresent() != secretKey.isPresent()
                    || (sessionToken.isPresent() && secretKey.isEmpty())
                    || accessKeyId.filter(String::isBlank).isPresent()) {
                throw new IllegalArgumentException("Incomplete S3 credentials");
            }
        }
    }

    record Ssh(String name, String host, int port, Optional<String> username,
            GitCredentialKind credentialKind, Optional<String> secret, Set<String> knownHosts)
            implements Connection {
        public Ssh {
            name = IdentifierRules.requireCanonical(name, "connection name");
            Objects.requireNonNull(host, "SSH host");
            username = Objects.requireNonNull(username, "SSH username");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SSH port");
            URI uri = sshUri(host, port, username, "/");
            if (uri.getHost() == null || !host.equals(uri.getHost())
                    || username.filter(value -> value.isBlank() || value.contains(":")).isPresent()) {
                throw new IllegalArgumentException("Invalid SSH authority");
            }
            Objects.requireNonNull(credentialKind, "SSH credential kind").requireTransport("ssh");
            secret = secretReference(secret);
            if (secret.isEmpty()) throw new IllegalArgumentException("SSH secret is required");
            knownHosts = GitProxyBinding.canonicalKnownHosts(knownHosts);
        }

        public URI upstream(String path) {
            URI authority = sshUri(host, port, username, "/");
            return GitProxyBinding.canonicalUpstream(URI.create("ssh://" + authority.getRawAuthority() + path));
        }

        public static Ssh fromUpstream(String name, URI upstream, GitCredentialKind kind,
                Optional<String> secret, Set<String> knownHosts) {
            URI uri = GitProxyBinding.canonicalUpstream(upstream);
            if (!"ssh".equals(uri.getScheme())) throw new IllegalArgumentException("SSH upstream is required");
            return new Ssh(name, uri.getHost(), uri.getPort() < 0 ? 22 : uri.getPort(),
                    Optional.ofNullable(uri.getUserInfo()), kind, secret, knownHosts);
        }
    }

    private static Optional<String> secretReference(Optional<String> reference) {
        return Objects.requireNonNull(reference, "connection secret")
                .map(value -> IdentifierRules.requireCanonical(value, "connection secret"));
    }

    private static URI sshUri(String host, int port, Optional<String> username, String path) {
        try {
            return new URI("ssh", username.orElse(null), host, port, path, null, null);
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid SSH authority");
        }
    }
}
