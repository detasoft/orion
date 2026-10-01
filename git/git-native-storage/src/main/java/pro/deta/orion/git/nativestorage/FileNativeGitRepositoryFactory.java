package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** File layout and metadata for opening native repositories, without an instance registry. */
final class FileNativeGitRepositoryFactory implements NativeGitRepositoryBackend {
    private static final String DEFAULT_HEAD = "refs/heads/main";
    private static final String METADATA_FILE = "orion-native-repository.properties";
    private static final String NAME_PROPERTY = "name";
    private static final String DEFAULT_HEAD_PROPERTY = "defaultHead";

    private final Path rootDirectory;

    FileNativeGitRepositoryFactory(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        createDirectories(this.rootDirectory);
    }

    @Override
    public List<String> repositoryNames() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(rootDirectory)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry.resolve(METADATA_FILE))) {
                    names.add(readMetadata(entry).name().value());
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to list native repositories", error);
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    @Override
    public boolean exists(RepositoryName name) {
        return Files.isRegularFile(repositoryDirectory(name).resolve(METADATA_FILE));
    }

    @Override
    public Result<NativeGitRepository> open(RepositoryName name) {
        if (!exists(name)) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND,
                    "Native repository does not exist: " + name.value());
        }
        Path directory = repositoryDirectory(name);
        RepositoryMetadata metadata = readMetadata(directory);
        return new Result.Success<>(NativeGitRepository.openLocal(metadata.name(), directory, metadata.defaultHead()));
    }

    @Override
    public Result<NativeGitRepository> create(RepositoryName name) {
        if (exists(name)) {
            return new Result.Failure<>(Result.FailureCode.FILE_ALREADY_EXISTS,
                    "Native repository already exists: " + name.value());
        }
        Path directory = repositoryDirectory(name);
        createDirectories(directory);
        Properties properties = new Properties();
        properties.setProperty(NAME_PROPERTY, name.value());
        properties.setProperty(DEFAULT_HEAD_PROPERTY, DEFAULT_HEAD);
        try (Writer writer = Files.newBufferedWriter(directory.resolve(METADATA_FILE), StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to create native repository metadata", error);
        }
        return open(name);
    }

    @Override
    public void close() {
    }

    private RepositoryMetadata readMetadata(Path directory) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(directory.resolve(METADATA_FILE), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read native repository metadata", error);
        }
        String name = properties.getProperty(NAME_PROPERTY);
        String defaultHead = properties.getProperty(DEFAULT_HEAD_PROPERTY, DEFAULT_HEAD);
        if (name == null) {
            throw new IllegalStateException("Native repository metadata is missing a name");
        }
        RepositoryName canonicalName = RepositoryName.parse(name);
        if (!canonicalName.value().equals(name)) {
            throw new IllegalArgumentException("Native repository metadata name is not canonical: " + name);
        }
        return new RepositoryMetadata(canonicalName, defaultHead);
    }

    private Path repositoryDirectory(RepositoryName name) {
        String id = HexFormat.of().formatHex(GitHashAlgorithm.SHA256.newDigest()
                .digest(name.value().getBytes(StandardCharsets.UTF_8)));
        return rootDirectory.resolve(id);
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to create directory: " + path, error);
        }
    }

    private record RepositoryMetadata(RepositoryName name, String defaultHead) {
    }
}
