package pro.deta.orion.git.s3;

import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.orion.Connection;
import pro.deta.orion.schema.orion.ConnectionReference;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.RepositoryAddress;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.schema.orion.S3StorageBinding;
import pro.deta.orion.util.Result;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Routes XML-bound repositories through the application-owned shared S3 transport. */
public final class ConfiguredNativeGitRepositoryProvider implements NativeGitRepositoryProvider {
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (S3NativeGitRepositoryProvider provider : providers.values()) provider.close();
        providers.clear();
        bootstrap.close();
    }

    private final NativeGitRepositoryProvider bootstrap;
    private final S3Transport client;
    private RuntimeConfiguration runtime;
    private final Map<RepositoryLocation, S3NativeGitRepositoryProvider> providers = new HashMap<>();
    private boolean closed;

    public ConfiguredNativeGitRepositoryProvider(NativeGitRepositoryProvider bootstrap, S3Transport client) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap repository provider");
        this.client = Objects.requireNonNull(client, "S3 transport");
    }

    public void activate(Supplier<OrionDocument> current, ConfigurationSecrets secrets,
            Predicate<String> bootstrapRepository) {
        RuntimeConfiguration configured = new RuntimeConfiguration(Objects.requireNonNull(current),
                Objects.requireNonNull(secrets), Objects.requireNonNull(bootstrapRepository));
        client.operation(() -> {
            synchronized (this) {
                requireOpen();
                bindings(current.get(), bootstrapRepository);
                runtime = configured;
            }
            return null;
        });
    }

    @Override
    public List<String> repositoryNames() {
        return client.operation(() -> {
            Map<String, NativeGitRepositoryProvider> providers = configuredProviders();
            TreeSet<String> names = new TreeSet<>(bootstrap.repositoryNames());
            for (Map.Entry<String, NativeGitRepositoryProvider> entry : providers.entrySet()) {
                names.remove(entry.getKey());
                if (entry.getValue().exists(entry.getKey())) names.add(entry.getKey());
            }
            return List.copyOf(names);
        });
    }

    @Override
    public boolean exists(String repositoryName) {
        return withProvider(repositoryName, provider -> provider.exists(repositoryName));
    }

    @Override
    public Result<NativeGitRepository> find(String repositoryName) {
        return repository(repositoryName, false);
    }

    @Override
    public Result<NativeGitRepository> create(String repositoryName) {
        return repository(repositoryName, true);
    }

    private Result<NativeGitRepository> repository(String repositoryName, boolean create) {
        String name = RepositoryName.parse(repositoryName).value();
        try {
            return withProvider(name, provider -> create ? provider.create(name) : provider.find(name));
        } catch (RuntimeException failure) {
            return new Result.Failure<>(Result.FailureCode.GENERAL,
                    "Cannot resolve configured repository storage", failure);
        }
    }

    private <T> T withProvider(String repositoryName, Function<NativeGitRepositoryProvider, T> operation) {
        String name = RepositoryName.parse(repositoryName).value();
        return client.operation(() -> operation.apply(provider(name)));
    }

    private synchronized NativeGitRepositoryProvider provider(String name) {
        requireOpen();
        RuntimeConfiguration configured = runtime;
        OrionDocument document = configured == null ? null : configured.current().get();
        Binding binding = document == null ? null : bindings(document, configured.bootstrapRepository()).get(name);
        return provider(document, configured == null ? null : configured.secrets(), name, binding);
    }

    private synchronized Map<String, NativeGitRepositoryProvider> configuredProviders() {
        requireOpen();
        RuntimeConfiguration configured = runtime;
        OrionDocument document = configured == null ? null : configured.current().get();
        Map<String, Binding> bindings = document == null ? Map.of()
                : bindings(document, configured.bootstrapRepository());
        Map<String, NativeGitRepositoryProvider> result = new HashMap<>();
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            result.put(entry.getKey(), provider(document, configured.secrets(), entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static Map<String, Binding> bindings(OrionDocument document, Predicate<String> bootstrapRepository) {
        Map<String, Binding> result = new HashMap<>();
        for (OrionDocument.Organization organization : document.organizations()) {
            for (OrionDocument.Team team : organization.teams()) {
                for (OrionDocument.Repository repository : team.repositories()) {
                    if (repository.storage().isEmpty()) continue;
                    String name = new RepositoryAddress(organization.id(), team.id(), repository.id()).toString();
                    if (name.startsWith("proxy/") || name.startsWith("bootstrap/") || bootstrapRepository.test(name)) {
                        throw new IllegalArgumentException("Bootstrap and proxy repositories must remain file-backed");
                    }
                    S3StorageBinding storage = repository.storage().orElseThrow();
                    Optional<OrganizationId> owner = storage.connection().scope() == ConnectionReference.Scope.SYSTEM
                            ? Optional.empty() : Optional.of(organization.id());
                    List<Connection> connections = owner.isEmpty() ? document.system().connections()
                            : organization.connections();
                    Connection.S3 connection = (Connection.S3) OrionDocument.findConnection(connections,
                            storage.connection().name());
                    result.put(name, new Binding(owner, connection, storage));
                }
            }
        }
        return result;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Configured repository provider is closed");
    }

    private NativeGitRepositoryProvider provider(OrionDocument document, ConfigurationSecrets secrets,
            String name, Binding binding) {
        requireOpen();
        if (binding == null) return bootstrap;
        Connection.S3 definition = binding.connection();
        Optional<AwsCredentialsProvider> credentials = Optional.empty();
        if (definition.secretKey().isPresent()) {
            char[] key = resolve(document, secrets, binding.owner(), definition.secretKey().orElseThrow());
            char[] token = new char[0];
            try {
                String id = definition.accessKeyId().orElseThrow();
                if (definition.sessionToken().isPresent()) {
                    token = resolve(document, secrets, binding.owner(), definition.sessionToken().orElseThrow());
                    credentials = Optional.of(StaticCredentialsProvider.create(
                            AwsSessionCredentials.create(id, new String(key), new String(token))));
                } else {
                    credentials = Optional.of(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(id, new String(key))));
                }
            } finally {
                Arrays.fill(key, '\0');
                Arrays.fill(token, '\0');
            }
        }
        String location = binding.storage().location().toString();
        S3NativeGitRepositoryProvider.Location parsed = S3NativeGitRepositoryProvider.parseLocation(location);
        String endpoint = definition.endpoint().map(Object::toString).orElse(null);
        RepositoryLocation key = new RepositoryLocation(name, definition.endpoint(), parsed);
        S3NativeGitRepositoryProvider provider = providers.get(key);
        if (provider != null) {
            provider.configure(client.overrides(endpoint, definition.region(),
                    definition.pathStyleAccess(), credentials));
            return provider;
        }
        provider = client.repositories(location, endpoint, definition.region(),
                definition.pathStyleAccess(), credentials);
        providers.put(key, provider);
        return provider;
    }

    private static char[] resolve(OrionDocument document, ConfigurationSecrets secrets,
            Optional<OrganizationId> owner, String id) {
        return owner.isEmpty() ? secrets.resolveSystem(document, id)
                : secrets.resolveOrganization(document, owner.orElseThrow(), id);
    }

    private record RepositoryLocation(String name, Optional<URI> endpoint,
            S3NativeGitRepositoryProvider.Location location) {}

    private record RuntimeConfiguration(Supplier<OrionDocument> current, ConfigurationSecrets secrets,
            Predicate<String> bootstrapRepository) {}
    private record Binding(Optional<OrganizationId> owner, Connection.S3 connection, S3StorageBinding storage) {}
}
