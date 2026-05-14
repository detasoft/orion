package pro.deta.orion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.moandjiezana.toml.Toml;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Map<String, String> query = queryParameters(location);
        String filePath = firstPresent(query, "path", "file");
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("Git configuration location must include path query parameter");
        }
        String ref = firstPresent(query, "ref", "branch");
        if (ref == null || ref.isBlank()) {
            ref = DEFAULT_REF;
        }
        return client.readFile(remoteUri(location), ref, filePath)
                .map(content -> new ConfigurationContent(filePath, content));
    }

    private static String remoteUri(ResourceLocation location) {
        if (!(location.scheme() instanceof ResourceScheme.Other other) || !other.value().startsWith(GIT_SCHEME_PREFIX)) {
            throw new IllegalArgumentException("Unsupported git configuration scheme: " + location.scheme().value());
        }
        return stripQueryAndFragment(location.withScheme(other.value().substring(GIT_SCHEME_PREFIX.length())).raw());
    }

    private static Map<String, String> queryParameters(ResourceLocation location) {
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

    private static String firstPresent(Map<String, String> values, String... keys) {
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

    private static String stripQueryAndFragment(String value) {
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
    Optional<byte[]> readFile(String remoteUri, String ref, String path);
}

final class JGitRepositoryFileClient implements GitRepositoryFileClient {
    @Override
    public Optional<byte[]> readFile(String remoteUri, String ref, String path) {
        Path cloneDirectory;
        try {
            cloneDirectory = Files.createTempDirectory("orion-config-git-");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create temporary git configuration clone directory", e);
        }

        try {
            clone(remoteUri, ref, cloneDirectory);
            Path file = cloneDirectory.resolve(path).normalize();
            if (!file.startsWith(cloneDirectory)) {
                throw new IllegalArgumentException("Git configuration path must stay inside repository: " + path);
            }
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read configuration file from git repository " + remoteUri, e);
        } finally {
            deleteRecursively(cloneDirectory);
        }
    }

    private static void clone(String remoteUri, String ref, Path directory) {
        try {
            var clone = Git.cloneRepository()
                    .setURI(remoteUri)
                    .setDirectory(directory.toFile());
            if (!"HEAD".equals(ref)) {
                clone.setBranch(ref);
            }
            try (Git ignored = clone.call()) {
                // Repository is cloned so the requested file can be read from the work tree.
            }
        } catch (GitAPIException e) {
            throw new IllegalStateException("Cannot clone configuration git repository " + remoteUri, e);
        }
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
        return client.readObject(bucket, key)
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

interface S3ObjectClient {
    Optional<byte[]> readObject(String bucket, String key);
}

final class AwsS3ObjectClient implements S3ObjectClient {
    @Override
    public Optional<byte[]> readObject(String bucket, String key) {
        try (S3Client client = S3Client.create()) {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return Optional.of(response.asByteArray());
        } catch (NoSuchBucketException | NoSuchKeyException e) {
            return Optional.empty();
        }
    }
}
