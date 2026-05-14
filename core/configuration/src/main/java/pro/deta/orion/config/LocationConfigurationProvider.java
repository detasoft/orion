package pro.deta.orion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.moandjiezana.toml.Toml;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class LocationConfigurationProvider implements ConfigurationProvider {
    private static final String[] DEFAULT_CONFIGURATION_LOCATIONS = new String[] { // order by priority
            "config.toml",
            "config.yml",
            "/etc/orion/orion.yml",
            "classpath://config.toml",
            "classpath://config.yml",
    };
    private final ObjectMapper yom = new ObjectMapper(new YAMLFactory());
    private final Toml toml = new Toml();
    private final String[] configurationLocations;
    private final boolean explicitConfigurationLocation;
    private final List<ConfigurationLocationReader> readers;

    public LocationConfigurationProvider() {
        this(DEFAULT_CONFIGURATION_LOCATIONS, false);
    }

    public LocationConfigurationProvider(String configurationLocation) {
        this(new String[]{requiredLocation(configurationLocation)}, true);
    }

    LocationConfigurationProvider(String[] configurationLocations, boolean explicitConfigurationLocation) {
        this(configurationLocations, explicitConfigurationLocation, defaultReaders());
    }

    LocationConfigurationProvider(
            String[] configurationLocations,
            boolean explicitConfigurationLocation,
            List<ConfigurationLocationReader> readers) {
        this.configurationLocations = configurationLocations.clone();
        this.explicitConfigurationLocation = explicitConfigurationLocation;
        this.readers = List.copyOf(readers);
    }

    @Override
    public OrionConfiguration readConfiguration() {
        return findConfiguration();
    }

    private OrionConfiguration findConfiguration() {
        for (String location : configurationLocations) {
            OrionConfiguration orionConfiguration = configurationLookup(location);
            if (orionConfiguration != null) {
                return orionConfiguration;
            }
        }
        if (explicitConfigurationLocation) {
            throw new IllegalArgumentException(
                    "Configuration location not found or unsupported: " + configurationLocations[0]);
        }
        return parseYaml(localResourceConfig("config.yml"));
    }

    public OrionConfiguration configurationLookup(String location) {
        if (location == null) {
            return null;
        }
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "Configuration location");
        for (ConfigurationLocationReader reader : readers) {
            if (!reader.supports(resourceLocation)) {
                continue;
            }
            Optional<ConfigurationContent> content = reader.read(resourceLocation);
            if (content.isEmpty()) {
                return null;
            }
            return parse(content.get());
        }
        return null;
    }

    public InputStream localResourceConfig(String name) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
    }

    private OrionConfiguration parse(ConfigurationContent content) {
        String sourceName = content.sourceName().toLowerCase(Locale.ROOT);
        try (InputStream input = new ByteArrayInputStream(content.content())) {
            if (sourceName.endsWith(".yaml") || sourceName.endsWith(".yml")) {
                return parseYaml(input);
            }
            if (sourceName.endsWith(".toml")) {
                return parseToml(input);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot close configuration input stream", e);
        }
        throw new IllegalArgumentException("Unsupported configuration format: " + content.sourceName());
    }

    private OrionConfiguration parseYaml(InputStream config) {
        try {
            return yom.readerFor(OrionConfiguration.class)
                    .readValue(config);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private OrionConfiguration parseToml(InputStream config) {
        return toml.read(config).to(OrionConfiguration.class);
    }

    private static List<ConfigurationLocationReader> defaultReaders() {
        return List.of(
                new FileConfigurationLocationReader(),
                new ClasspathConfigurationLocationReader(),
                new GitConfigurationLocationReader(new JGitRepositoryFileClient()),
                new S3ConfigurationLocationReader(new AwsS3ObjectClient()));
    }

    private static String requiredLocation(String configurationLocation) {
        if (configurationLocation == null || configurationLocation.isBlank()) {
            throw new IllegalArgumentException("Configuration location must not be blank");
        }
        return configurationLocation;
    }
}

record ConfigurationContent(String sourceName, byte[] content) {
    ConfigurationContent {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Configuration source name must not be blank");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}

interface ConfigurationLocationReader {
    boolean supports(ResourceLocation location);

    Optional<ConfigurationContent> read(ResourceLocation location);
}

final class FileConfigurationLocationReader implements ConfigurationLocationReader {
    @Override
    public boolean supports(ResourceLocation location) {
        return switch (location.scheme()) {
            case ResourceScheme.Empty ignored -> true;
            case ResourceScheme.File ignored -> true;
            default -> false;
        };
    }

    @Override
    public Optional<ConfigurationContent> read(ResourceLocation location) {
        Path path = filePath(location);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ConfigurationContent(path.toString(), Files.readAllBytes(path)));
        } catch (IOException e) {
            throw new IllegalStateException("Error while reading configuration from " + location.raw(), e);
        }
    }

    private static Path filePath(ResourceLocation location) {
        return switch (location.scheme()) {
            case ResourceScheme.Empty ignored -> Path.of(location.raw());
            case ResourceScheme.File ignored -> Path.of(location.pathOrSchemeSpecificPart(
                    "File configuration location must include a path"));
            default -> throw new IllegalArgumentException("Unsupported file configuration location: " + location.raw());
        };
    }
}

final class ClasspathConfigurationLocationReader implements ConfigurationLocationReader {
    @Override
    public boolean supports(ResourceLocation location) {
        return location.scheme() instanceof ResourceScheme.Other other && "classpath".equals(other.value());
    }

    @Override
    public Optional<ConfigurationContent> read(ResourceLocation location) {
        String resourceName = classpathResourceName(location);
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                return Optional.empty();
            }
            return Optional.of(new ConfigurationContent(resourceName, input.readAllBytes()));
        } catch (IOException e) {
            throw new IllegalStateException("Error while reading configuration from " + location.raw(), e);
        }
    }

    private static String classpathResourceName(ResourceLocation location) {
        String resourceName = location.normalizedRelativePath();
        if (resourceName.isBlank()) {
            throw new IllegalArgumentException("Classpath configuration location must include resource name");
        }
        return resourceName;
    }
}

final class GitConfigurationLocationReader implements ConfigurationLocationReader {
    private static final String GIT_SCHEME_PREFIX = "git+";
    private static final String DEFAULT_REF = "HEAD";

    private final GitRepositoryFileClient client;

    GitConfigurationLocationReader(GitRepositoryFileClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(ResourceLocation location) {
        return location.scheme() instanceof ResourceScheme.Other other
                && ("git+ssh".equals(other.value())
                || "git+http".equals(other.value())
                || "git+https".equals(other.value()));
    }

    @Override
    public Optional<ConfigurationContent> read(ResourceLocation location) {
        Map<String, String> query = ConfigurationLocationParameters.query(location);
        String filePath = ConfigurationLocationParameters.firstPresent(query, "path", "file");
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("Git configuration location must include path query parameter");
        }
        String ref = ConfigurationLocationParameters.firstPresent(query, "ref", "branch");
        if (ref == null || ref.isBlank()) {
            ref = DEFAULT_REF;
        }
        return client.readFile(new GitConfigurationObject(remoteUri(location), ref, filePath, query))
                .map(content -> new ConfigurationContent(filePath, content));
    }

    private static String remoteUri(ResourceLocation location) {
        if (!(location.scheme() instanceof ResourceScheme.Other other) || !other.value().startsWith(GIT_SCHEME_PREFIX)) {
            throw new IllegalArgumentException("Unsupported git configuration scheme: " + location.scheme().value());
        }
        return ConfigurationLocationUri.stripQueryAndFragment(
                location.withScheme(other.value().substring(GIT_SCHEME_PREFIX.length())).raw());
    }
}

final class ConfigurationLocationParameters {
    private ConfigurationLocationParameters() {
    }

    static Map<String, String> query(ResourceLocation location) {
        String rawQuery = location.uri().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String key = separator >= 0 ? part.substring(0, separator) : part;
            String value = separator >= 0 ? part.substring(separator + 1) : "";
            result.put(decode(key), decode(value));
        }
        return result;
    }

    static String firstPresent(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}

final class ConfigurationLocationUri {
    private ConfigurationLocationUri() {
    }

    static String stripQueryAndFragment(String value) {
        int end = value.length();
        int queryStart = value.indexOf('?');
        if (queryStart >= 0) {
            end = Math.min(end, queryStart);
        }
        int fragmentStart = value.indexOf('#');
        if (fragmentStart >= 0) {
            end = Math.min(end, fragmentStart);
        }
        return value.substring(0, end);
    }
}

interface GitRepositoryFileClient {
    Optional<byte[]> readFile(GitConfigurationObject object);
}

record GitConfigurationObject(String remoteUri, String ref, String path, Map<String, String> auth) {
    GitConfigurationObject {
        if (remoteUri == null || remoteUri.isBlank()) {
            throw new IllegalArgumentException("Git configuration remote URI must not be blank");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Git configuration ref must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Git configuration path must not be blank");
        }
        auth = Map.copyOf(auth == null ? Map.of() : auth);
    }
}

final class JGitRepositoryFileClient implements GitRepositoryFileClient {
    @Override
    public Optional<byte[]> readFile(GitConfigurationObject object) {
        Path cloneDirectory;
        try {
            cloneDirectory = Files.createTempDirectory("orion-config-git-");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create temporary git configuration clone directory", e);
        }

        try {
            clone(object, cloneDirectory);
            Path file = cloneDirectory.resolve(object.path()).normalize();
            if (!file.startsWith(cloneDirectory)) {
                throw new IllegalArgumentException("Git configuration path must stay inside repository: " + object.path());
            }
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read configuration file from git repository " + object.remoteUri(),
                    e);
        } finally {
            deleteRecursively(cloneDirectory);
        }
    }

    private static void clone(GitConfigurationObject object, Path directory) {
        try {
            CloneCommand clone = configure(
                    Git.cloneRepository()
                            .setURI(object.remoteUri())
                            .setDirectory(directory.toFile()),
                    object,
                    directory);
            if (!"HEAD".equals(object.ref())) {
                clone.setBranch(object.ref());
            }
            try (Git ignored = clone.call()) {
                // Repository is cloned so the requested file can be read from the work tree.
            }
        } catch (GitAPIException e) {
            throw new IllegalStateException("Cannot clone configuration git repository " + object.remoteUri(), e);
        }
    }

    private static CloneCommand configure(CloneCommand command, GitConfigurationObject object, Path worktree) {
        CredentialsProvider credentialsProvider = credentialsProvider(object.auth());
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        TransportConfigCallback transportConfigCallback = transportConfigCallback(object.auth(), worktree);
        if (transportConfigCallback != null) {
            command.setTransportConfigCallback(transportConfigCallback);
        }
        return command;
    }

    private static CredentialsProvider credentialsProvider(Map<String, String> auth) {
        if (auth == null || (!auth.containsKey("username") && !auth.containsKey("password"))) {
            return null;
        }
        String username = auth.getOrDefault("username", "");
        String password = ConfigurationLocationSecret.optionalSecret("git.password", auth.get("password"));
        return new UsernamePasswordCredentialsProvider(username, password);
    }

    private static TransportConfigCallback transportConfigCallback(Map<String, String> auth, Path worktree) {
        if (auth == null || !auth.containsKey("privateKey")) {
            return null;
        }
        Path privateKey = ConfigurationLocationSecret.fileReference("git.privateKey", auth.get("privateKey"));
        Path sshDirectory = worktree.resolve(".ssh").toAbsolutePath().normalize();
        SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
                .setHomeDirectory(worktree.toFile())
                .setSshDirectory(sshDirectory.toFile())
                .setDefaultIdentities(ignored -> List.of(privateKey));
        if (auth.containsKey("knownHosts")) {
            Path knownHosts = ConfigurationLocationSecret.fileReference("git.knownHosts", auth.get("knownHosts"));
            builder.setDefaultKnownHostsFiles(ignored -> List.of(knownHosts));
        }
        if (auth.containsKey("passphrase")) {
            char[] passphrase = ConfigurationLocationSecret.optionalSecret(
                    "git.passphrase",
                    auth.get("passphrase")).toCharArray();
            builder.setKeyPasswordProvider(ignored -> new StaticKeyPasswordProvider(passphrase));
        }
        SshdSessionFactory sessionFactory = builder.build(null);
        return transport -> {
            if (transport instanceof SshTransport sshTransport) {
                sshTransport.setSshSessionFactory(sessionFactory);
            }
        };
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    for (Path entry : entries) {
                        deleteRecursively(entry);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static final class StaticKeyPasswordProvider implements KeyPasswordProvider {
        private final char[] passphrase;
        private int attempts;

        private StaticKeyPasswordProvider(char[] passphrase) {
            this.passphrase = passphrase.clone();
        }

        @Override
        public char[] getPassphrase(org.eclipse.jgit.transport.URIish uri, int attempt) {
            return passphrase.clone();
        }

        @Override
        public void setAttempts(int attempts) {
            this.attempts = attempts;
        }

        @Override
        public int getAttempts() {
            return attempts;
        }

        @Override
        public boolean keyLoaded(org.eclipse.jgit.transport.URIish uri, int attempt, Exception error)
                throws IOException, GeneralSecurityException {
            if (error != null) {
                throw new GeneralSecurityException(error);
            }
            return true;
        }
    }
}

final class S3ConfigurationLocationReader implements ConfigurationLocationReader {
    private final S3ObjectClient client;

    S3ConfigurationLocationReader(S3ObjectClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(ResourceLocation location) {
        return location.scheme() instanceof ResourceScheme.Other other && "s3".equals(other.value());
    }

    @Override
    public Optional<ConfigurationContent> read(ResourceLocation location) {
        String bucket = location.host();
        String key = stripLeadingSlash(location.path());
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 configuration location must include bucket name");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("S3 configuration location must include object key");
        }
        return client.readObject(new S3ConfigurationObject(
                        bucket,
                        key,
                        ConfigurationLocationParameters.query(location)))
                .map(content -> new ConfigurationContent(key, content));
    }

    private static String stripLeadingSlash(String value) {
        if (value == null) {
            return "";
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}

record S3ConfigurationObject(String bucket, String key, Map<String, String> auth) {
    S3ConfigurationObject {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 configuration bucket must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("S3 configuration object key must not be blank");
        }
        auth = Map.copyOf(auth == null ? Map.of() : auth);
    }
}

interface S3ObjectClient {
    Optional<byte[]> readObject(S3ConfigurationObject object);
}

final class AwsS3ObjectClient implements S3ObjectClient {
    @Override
    public Optional<byte[]> readObject(S3ConfigurationObject object) {
        try (S3Client client = createClient(object.auth())) {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(object.bucket())
                    .key(object.key())
                    .build());
            return Optional.of(response.asByteArray());
        } catch (NoSuchBucketException | NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static S3Client createClient(Map<String, String> auth) {
        var builder = S3Client.builder()
                .region(Region.of(auth.getOrDefault("region", "us-east-1")));

        String endpoint = auth.get("endpoint");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            builder.serviceConfiguration(s3Configuration(true));
        } else if ("true".equalsIgnoreCase(auth.get("pathStyleAccess"))) {
            builder.serviceConfiguration(s3Configuration(true));
        }

        if (auth.containsKey("accessKeyId") || auth.containsKey("secretAccessKey")) {
            String accessKeyId = auth.get("accessKeyId");
            if (accessKeyId == null || accessKeyId.isBlank()) {
                throw new IllegalArgumentException("s3.accessKeyId must not be blank");
            }
            String secretAccessKey = ConfigurationLocationSecret.requiredSecret(
                    "s3.secretAccessKey",
                    auth.get("secretAccessKey"));
            if (auth.containsKey("sessionToken")) {
                String sessionToken = ConfigurationLocationSecret.requiredSecret(
                        "s3.sessionToken",
                        auth.get("sessionToken"));
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)));
            } else {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
            }
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    private static S3Configuration s3Configuration(boolean pathStyleAccess) {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .chunkedEncodingEnabled(false)
                .build();
    }
}

final class ConfigurationLocationSecret {
    private ConfigurationLocationSecret() {
    }

    static String requiredSecret(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.startsWith("env:")) {
            String variableName = value.substring("env:".length());
            String secret = System.getenv(variableName);
            if (secret == null) {
                throw new IllegalArgumentException(name + " environment variable is not set: " + variableName);
            }
            return secret;
        }
        if (value.startsWith("file:")) {
            try {
                return Files.readString(fileReference(name, value), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + name + " from " + value, e);
            }
        }
        throw new IllegalArgumentException(name + " must use env: or file: reference");
    }

    static String optionalSecret(String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return requiredSecret(name, value);
    }

    static Path fileReference(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        ResourceLocation location = ResourceLocation.parse(value, name);
        return switch (location.scheme()) {
            case ResourceScheme.File ignored -> Path.of(location.pathOrSchemeSpecificPart(name + " must include a path"));
            default -> throw new IllegalArgumentException(name + " must use file: reference");
        };
    }
}
