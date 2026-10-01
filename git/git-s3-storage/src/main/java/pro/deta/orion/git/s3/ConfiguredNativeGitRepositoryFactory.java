package pro.deta.orion.git.s3;

import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.schema.orion.v2.Connection;
import pro.deta.orion.schema.orion.v2.ConnectionReference;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.RepositoryAddress;
import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.schema.orion.v2.S3StorageBinding;
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
public final class ConfiguredNativeGitRepositoryFactory implements NativeGitRepositoryBackend {
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            for (S3NativeGitRepositoryFactory factory : factories.values()) factory.close();
            factories.clear();
            bootstrap.close();
        } finally {
            client.close();
        }
    }

    private final NativeGitRepositoryBackend bootstrap;
    private final S3Transport client;
    private volatile RuntimeConfiguration runtime;
    private final Map<RepositoryLocation, S3NativeGitRepositoryFactory> factories = new HashMap<>();
    private volatile boolean closed;

    public ConfiguredNativeGitRepositoryFactory(NativeGitRepositoryBackend bootstrap, S3Transport client) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap repository factory");
        this.client = Objects.requireNonNull(client, "S3 transport");
    }

    public S3Transport transport() {
        return client;
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
            requireOpen();
            RuntimeConfiguration configured = runtime;
            OrionDocument document = configured == null ? null : configured.current().get();
            Map<String, Binding> bindings = document == null ? Map.of()
                    : bindings(document, configured.bootstrapRepository());
            TreeSet<String> names = new TreeSet<>(bootstrap.repositoryNames());
            for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
                names.remove(entry.getKey());
                if (factory(document, configured.secrets(), entry.getKey(), entry.getValue())
                        .exists(RepositoryName.parse(entry.getKey()))) {
                    names.add(entry.getKey());
                }
            }
            return List.copyOf(names);
        });
    }

    @Override
    public boolean exists(RepositoryName repositoryName) {
        return withFactory(repositoryName, factory -> factory.exists(repositoryName));
    }

    @Override
    public Result<NativeGitRepository> open(RepositoryName repositoryName) {
        return repository(repositoryName, false);
    }

    @Override
    public Result<NativeGitRepository> create(RepositoryName repositoryName) {
        return repository(repositoryName, true);
    }

    @Override
    public NativeGitRepositoryBackend owner(RepositoryName name) {
        return withFactory(name, selected -> selected.owner(name));
    }

    private Result<NativeGitRepository> repository(RepositoryName name, boolean create) {
        try {
            return withFactory(name, factory -> create ? factory.create(name) : factory.open(name));
        } catch (RuntimeException failure) {
            return new Result.Failure<>(Result.FailureCode.GENERAL,
                    "Cannot resolve configured repository storage", failure);
        }
    }

    private <T> T withFactory(RepositoryName name, Function<NativeGitRepositoryBackend, T> operation) {
        return client.operation(() -> {
            requireOpen();
            RuntimeConfiguration configured = runtime;
            OrionDocument document = configured == null ? null : configured.current().get();
            Binding binding = document == null ? null : bindings(document, configured.bootstrapRepository())
                    .get(name.value());
            NativeGitRepositoryBackend selected = binding == null ? bootstrap
                    : factory(document, configured.secrets(), name.value(), binding);
            return operation.apply(selected);
        });
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Configured repository factory is closed");
    }

    private static Map<String, Binding> bindings(OrionDocument document,
            Predicate<String> bootstrapRepository) {
        Map<String, Binding> result = new HashMap<>();
        for (OrionDocument.Organization organization : document.organizations()) {
            for (OrionDocument.Team team : organization.teams()) {
                for (OrionDocument.Repository repository : team.repositories()) {
                    if (repository.storage().isEmpty()) continue;
                    String name = new RepositoryAddress(organization.id(), team.id(), repository.id())
                            .toString();
                    if (name.startsWith("proxy/") || name.startsWith("bootstrap/")
                            || bootstrapRepository.test(name)) {
                        throw new IllegalArgumentException(
                                "Bootstrap and proxy repositories must remain file-backed");
                    }
                    S3StorageBinding storage = repository.storage().orElseThrow();
                    Optional<OrganizationId> owner =
                            storage.connection().scope() == ConnectionReference.Scope.SYSTEM
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

    private synchronized NativeGitRepositoryBackend factory(OrionDocument document, ConfigurationSecrets secrets,
            String name, Binding binding) {
        requireOpen();
        Connection.S3 definition = binding.connection();
        Optional<AwsCredentialsProvider> credentials = Optional.empty();
        if (definition.secretKey().isPresent()) {
            char[] key = resolve(document, secrets, binding.owner(), definition.secretKey().orElseThrow());
            char[] token = new char[0];
            try {
                String id = definition.accessKeyId().orElseThrow();
                if (definition.sessionToken().isPresent()) {
                    token = resolve(document, secrets, binding.owner(),
                            definition.sessionToken().orElseThrow());
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
        String endpoint = definition.endpoint().map(Object::toString).orElse(null);
        RepositoryLocation key = new RepositoryLocation(name, definition.endpoint(),
                S3NativeGitRepositoryFactory.parseLocation(location));
        software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration overrides = client.overrides(
                endpoint, definition.region(), definition.pathStyleAccess(), credentials);
        S3NativeGitRepositoryFactory selected = factories.get(key);
        if (selected != null) {
            selected.configure(overrides);
            return selected;
        }
        selected = S3NativeGitRepositoryFactory.shared(location, client, overrides);
        factories.put(key, selected);
        return selected;
    }

    private static char[] resolve(OrionDocument document, ConfigurationSecrets secrets,
            Optional<OrganizationId> owner, String id) {
        return owner.isEmpty() ? secrets.resolveSystem(document, id)
                : secrets.resolveOrganization(document, owner.orElseThrow(), id);
    }

    private record RepositoryLocation(String name, Optional<URI> endpoint,
            S3NativeGitRepositoryFactory.Location location) {}

    private record RuntimeConfiguration(Supplier<OrionDocument> current, ConfigurationSecrets secrets,
            Predicate<String> bootstrapRepository) {}
    private record Binding(Optional<OrganizationId> owner, Connection.S3 connection,
            S3StorageBinding storage) {}
}
