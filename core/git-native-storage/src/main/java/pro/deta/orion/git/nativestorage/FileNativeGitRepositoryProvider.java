package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.LocalPackObjectDirectory;
import pro.deta.orion.git.nativestorage.pack.LocalPackPublicationStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FileNativeGitRepositoryProvider implements NativeGitRepositoryProvider {
    private static final String DEFAULT_HEAD = "refs/heads/main";
    private static final String METADATA_FILE = "orion-native-repository.properties";
    private static final String NAME_PROPERTY = "name";
    private static final String DEFAULT_HEAD_PROPERTY = "defaultHead";

    private final Path rootDirectory;
    private final ConcurrentMap<String, NativeGitRepository> repositories = new ConcurrentHashMap<>();

    public FileNativeGitRepositoryProvider(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(
                rootDirectory,
                "rootDirectory").toAbsolutePath().normalize();
        createDirectories(this.rootDirectory);
    }

    @Override
    public synchronized boolean exists(String repositoryName) {
        String name = requireName(repositoryName);
        return Files.isRegularFile(metadataPath(name));
    }

    @Override
    public synchronized Result<NativeGitRepository> find(String repositoryName) {
        String name = requireName(repositoryName);
        if (!Files.isRegularFile(metadataPath(name))) {
            return new Result.Failure<>(
                    Result.FailureCode.NOT_FOUND,
                    "Native repository does not exist: " + name);
        }
        return new Result.Success<>(open(name));
    }

    @Override
    public synchronized Result<NativeGitRepository> create(String repositoryName) {
        String name = requireName(repositoryName);
        Path metadata = metadataPath(name);
        if (Files.isRegularFile(metadata)) {
            return new Result.Failure<>(
                    Result.FailureCode.FILE_ALREADY_EXISTS,
                    "Native repository already exists: " + name);
        }
        createRepository(name);
        return new Result.Success<>(open(name));
    }

    private NativeGitRepository open(String name) {
        return repositories.computeIfAbsent(name, ignored -> {
            Path repositoryDirectory = repositoryDirectory(name);
            RepositoryMetadata metadata = readMetadata(repositoryDirectory);
            return new NativeGitRepository(
                    metadata.name(),
                    new LooseRefStore(repositoryDirectory),
                    new LooseObjectStore(repositoryDirectory.resolve("objects")),
                    metadata.defaultHead(),
                    new LocalPackPublicationStore(repositoryDirectory),
                    new LocalPackObjectDirectory(repositoryDirectory));
        });
    }

    private void createRepository(String name) {
        Path repositoryDirectory = repositoryDirectory(name);
        createDirectories(repositoryDirectory.resolve("objects"));
        createDirectories(repositoryDirectory.resolve("refs"));
        createDirectories(repositoryDirectory.resolve("packs"));
        createDirectories(repositoryDirectory.resolve("tmp").resolve("pack-publication"));
        Properties properties = new Properties();
        properties.setProperty(NAME_PROPERTY, name);
        properties.setProperty(DEFAULT_HEAD_PROPERTY, DEFAULT_HEAD);
        Path metadata = repositoryDirectory.resolve(METADATA_FILE);
        try (Writer writer = Files.newBufferedWriter(metadata, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to create native repository metadata", error);
        }
    }

    private RepositoryMetadata readMetadata(Path repositoryDirectory) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                repositoryDirectory.resolve(METADATA_FILE),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read native repository metadata", error);
        }
        String name = properties.getProperty(NAME_PROPERTY);
        String defaultHead = properties.getProperty(DEFAULT_HEAD_PROPERTY, DEFAULT_HEAD);
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Native repository metadata is missing a name");
        }
        return new RepositoryMetadata(name, defaultHead);
    }

    private Path metadataPath(String repositoryName) {
        return repositoryDirectory(repositoryName).resolve(METADATA_FILE);
    }

    private Path repositoryDirectory(String repositoryName) {
        return rootDirectory.resolve(repositoryId(repositoryName));
    }

    private static String requireName(String repositoryName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("repositoryName must not be blank");
        }
        return repositoryName;
    }

    private static String repositoryId(String repositoryName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(repositoryName.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to create directory: " + path, error);
        }
    }

    private record RepositoryMetadata(String name, String defaultHead) {
        private RepositoryMetadata {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(defaultHead, "defaultHead");
        }
    }
}
