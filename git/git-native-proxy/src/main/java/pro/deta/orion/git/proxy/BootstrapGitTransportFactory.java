package pro.deta.orion.git.proxy;

import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitCredentials;
import pro.deta.orion.git.client.GitRemoteClientTransport;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.OrionDocument;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

final class BootstrapGitTransportFactory {
    private static final GitClientOptions OPTIONS = GitClientOptions.defaults();

    private final Function<BootstrapGitLocation, Connection> connection;

    BootstrapGitTransportFactory(BootstrapSecretResolver secretResolver) {
        Objects.requireNonNull(secretResolver, "secretResolver");
        connection = location -> {
            if (location.credentialKind() == GitCredentialKind.NONE) {
                return new Connection(location, new char[0]);
            }
            try (BootstrapSecret secret = secretResolver.resolve(
                    "Remote Git credential", location.credentialReference())) {
                return new Connection(location, secret.copy());
            }
        };
    }

    private BootstrapGitTransportFactory(Function<BootstrapGitLocation, Connection> connection) {
        this.connection = connection;
    }

    static BootstrapGitTransportFactory persistent(
            Supplier<OrionDocument> current, ConfigurationSecrets secrets) {
        Objects.requireNonNull(current, "current configuration");
        Objects.requireNonNull(secrets, "configuration secrets");
        return new BootstrapGitTransportFactory(original -> {
            OrionDocument snapshot = current.get();
            for (var binding : snapshot.system().proxies()) {
                BootstrapGitLocation location = BootstrapGitLocation.persistent(binding);
                if (location.proxyName().equals(original.proxyName())) {
                    char[] credential = binding.secret().isPresent()
                            ? secrets.resolveSystem(snapshot, binding.secret().orElseThrow()) : new char[0];
                    return new Connection(location, credential);
                }
            }
            throw new BootstrapGitProxyException("persistent binding lookup");
        });
    }

    <T> T withTransport(
            BootstrapGitLocation original,
            TransportOperation<T> operation) throws Exception {
        Objects.requireNonNull(original, "location");
        Objects.requireNonNull(operation, "operation");
        Connection selected = connection.apply(original);
        BootstrapGitLocation location = selected.location();
        char[] characters = selected.credential();
        try {
            GitTransportScheme scheme = GitTransportScheme.from(location.remoteUri());
            if (scheme == GitTransportScheme.SSH) {
                if (location.knownHosts() == null) {
                    throw new BootstrapGitProxyException("SSH host-key configuration");
                }
                requireProtectedKnownHosts(location.knownHosts());
            }
            boolean http = scheme == GitTransportScheme.HTTP || scheme == GitTransportScheme.HTTPS;
            try (GitCredentials credentials = new GitCredentials(
                    location.credentialKind(),
                    Objects.requireNonNullElse(location.credentialUsername(), ""), characters);
                 HttpClient client = http ? HttpClient.newBuilder()
                         .connectTimeout(OPTIONS.connectTimeout())
                         .followRedirects(HttpClient.Redirect.NEVER).build() : null) {
                return operation.run(location, new GitRemoteClientTransport(
                        client, credentials, location.knownHosts(), scheme == GitTransportScheme.HTTP));
            }
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    private record Connection(BootstrapGitLocation location, char[] credential) {
    }

    private static void requireProtectedKnownHosts(Path knownHosts) {
        try {
            BootstrapSecretResolver.requireIntegrityProtectedFile(knownHosts, "SSH known-hosts");
        } catch (IOException | RuntimeException error) {
            throw new BootstrapGitProxyException("SSH host-key configuration");
        }
    }

    @FunctionalInterface
    interface TransportOperation<T> {
        T run(BootstrapGitLocation location, GitClientTransport transport) throws Exception;
    }
}
