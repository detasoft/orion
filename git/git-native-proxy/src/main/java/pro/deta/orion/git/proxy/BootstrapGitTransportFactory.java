package pro.deta.orion.git.proxy;

import java.util.IdentityHashMap;
import java.util.Collections;
import pro.deta.orion.decision.Decisionable;
import pro.deta.orion.decision.DecisionRequiredException;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.slf4j.LoggerFactory;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.decision.ConnectionFailureHandler;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.git.client.GitSshClientTransport.HostKeyRejectedException;
import java.util.function.BiFunction;
import pro.deta.orion.git.client.GitSshClientTransport;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitCredentials;
import pro.deta.orion.git.client.GitRemoteClientTransport;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;

import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.security.PublicKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

final class BootstrapGitTransportFactory {
    private static final GitClientOptions OPTIONS = GitClientOptions.defaults();

    private final Function<BootstrapGitLocation, Connection> connection;
    private final Function<BootstrapGitLocation, List<String>> bootstrapSections;
    private final ConnectionFailureHandler connectionFailures;
    private final BiFunction<GitProxyBinding, HostKeyRejectedException, Decision> hostKeyDecisions;
    private final Map<BootstrapGitLocation, Set<String>> acceptedKeys = new ConcurrentHashMap<>();

    BootstrapGitTransportFactory(BootstrapSecretResolver secretResolver,
            Function<BootstrapGitLocation, List<String>> bootstrapSections) {
        this.bootstrapSections = Objects.requireNonNull(bootstrapSections, "bootstrap sections");
        connectionFailures = null;
        hostKeyDecisions = null;
        Objects.requireNonNull(secretResolver, "secretResolver");
        connection = location -> {
            if (location.credentialKind() == GitCredentialKind.NONE) {
                return new Connection(location, new char[0], null);
            }
            try (BootstrapSecret secret = secretResolver.resolve(
                    "Remote Git credential", location.credentialReference())) {
                return new Connection(location, secret.copy(), null);
            }
        };
    }

    private BootstrapGitTransportFactory(Function<BootstrapGitLocation, Connection> connection,
            ConnectionFailureHandler connectionFailures,
            BiFunction<GitProxyBinding, HostKeyRejectedException, Decision> hostKeyDecisions) {
        this.connection = connection;
        this.connectionFailures = connectionFailures;
        this.hostKeyDecisions = hostKeyDecisions;
        bootstrapSections = null;
    }

    static BootstrapGitTransportFactory persistent(
            Supplier<OrionDocument> current, ConfigurationSecrets secrets,
            ConnectionFailureHandler connectionFailures,
            BiFunction<GitProxyBinding, HostKeyRejectedException, Decision> hostKeyDecisions) {
        Objects.requireNonNull(current, "current configuration");
        Objects.requireNonNull(secrets, "configuration secrets");
        return new BootstrapGitTransportFactory(original -> {
            OrionDocument snapshot = current.get();
            for (var binding : snapshot.system().proxies()) {
                BootstrapGitLocation location = BootstrapGitLocation.persistent(binding);
                if (location.proxyName().equals(original.proxyName())) {
                    char[] credential = binding.secret().isPresent()
                            ? secrets.resolveSystem(snapshot, binding.secret().orElseThrow()) : new char[0];
                    return new Connection(location, credential, binding);
                }
            }
            throw new BootstrapGitProxyException("persistent binding lookup");
        }, connectionFailures, hostKeyDecisions);
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
            boolean http = scheme == GitTransportScheme.HTTP || scheme == GitTransportScheme.HTTPS;
            try (GitCredentials credentials = new GitCredentials(
                    location.credentialKind(),
                    Objects.requireNonNullElse(location.credentialUsername(), ""), characters);
                 HttpClient client = http ? HttpClient.newBuilder()
                         .connectTimeout(OPTIONS.connectTimeout())
                         .followRedirects(HttpClient.Redirect.NEVER).build() : null) {
                if (scheme == GitTransportScheme.SSH && bootstrapSections != null) {
                    return operation.run(location, GitSshClientTransport.verifyingHostKeys(
                            (session, address, key) -> verifyBootstrapKey(location, key), credentials));
                }
                return operation.run(location, new GitRemoteClientTransport(
                        client, credentials, location.knownHosts(), scheme == GitTransportScheme.HTTP));
            }
        } catch (Exception failure) {
            if (selected.binding() == null || connectionFailures == null) throw failure;
            Exception prepared = failure;
            if (hostKeyDecisions != null) {
                Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
                    if (cause instanceof Decisionable) break;
                    if (cause instanceof HostKeyRejectedException rejected) {
                        prepared = new DecisionRequiredException(
                                hostKeyDecisions.apply(selected.binding(), rejected), failure);
                        break;
                    }
                }
            }
            throw connectionFailures.handle(prepared);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    Set<String> knownHosts(BootstrapGitLocation location) {
        Set<String> keys = new TreeSet<>(location.knownHosts());
        keys.addAll(acceptedKeys.getOrDefault(location, Set.of()));
        return Set.copyOf(keys);
    }

    private boolean verifyBootstrapKey(BootstrapGitLocation location, PublicKey serverKey) {
        String key = PublicKeyEntry.toString(serverKey);
        if (location.knownHosts().contains(key)) return true;
        List<String> sections = bootstrapSections.apply(location);
        if (sections.isEmpty()) return false;
        Set<String> observed = acceptedKeys.getOrDefault(location, Set.of());
        boolean accept = location.knownHosts().isEmpty() && (observed.isEmpty() || observed.contains(key));
        if (accept) {
            acceptedKeys.computeIfAbsent(location, ignored -> ConcurrentHashMap.newKeySet()).add(key);
        }
        Set<String> suggestedKeys = new TreeSet<>(knownHosts(location));
        suggestedKeys.add(key);
        StringBuilder yaml = new StringBuilder("bootstrap:\n");
        for (String section : sections) {
            yaml.append("  ").append(section).append(":\n    auth:\n      knownHosts: |\n");
            for (String suggested : suggestedKeys) yaml.append("        ").append(suggested).append('\n');
        }
        LoggerFactory.getLogger(BootstrapGitTransportFactory.class).warn(
                "Bootstrap SSH host key {} for [{}]:{}; fingerprint {}. "
                        + "Verify this key independently, then merge into orion.yml, preserving other auth settings:\n{}",
                accept ? "not verified; accepted for this run" : "rejected; configured keys do not match",
                location.remoteUri().getHost(), location.remoteUri().getPort() < 0 ? 22 : location.remoteUri().getPort(),
                KeyUtils.getFingerPrint(serverKey), yaml);
        return accept;
    }

    private record Connection(BootstrapGitLocation location, char[] credential, GitProxyBinding binding) {
    }

    @FunctionalInterface
    interface TransportOperation<T> {
        T run(BootstrapGitLocation location, GitClientTransport transport) throws Exception;
    }
}
