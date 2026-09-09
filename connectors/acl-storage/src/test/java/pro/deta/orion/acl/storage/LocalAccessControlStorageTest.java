package pro.deta.orion.acl.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAccessControlStorageTest {
    private static final String ACL_PATH = "config/orion.xml";
    private static final String ROLES_PATH = "roles/custom.xml";

    @TempDir
    private Path root;

    @Test
    void loadsExistingConfiguredFile() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.write(root.resolve(ACL_PATH), bytes("existing ACL"));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("existing ACL");

        assertThat(snapshot.files()).containsOnlyKeys(ACL_PATH)
                .containsEntry(ACL_PATH, bytes("existing ACL"));
        assertThat(snapshot.version()).isEmpty();
    }

    @Test
    void reportsMissingConfiguredFile() {
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));

        Result<AccessControlSnapshot> result = storage.load();

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
    }

    @Test
    void initialSaveCreatesRootAndParentDirectories() throws Exception {
        Path directory = root.resolve("new-acl");
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(directory));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("initial ACL")),
                new AccessControlSaveRequest("initial ACL", UserEmail.EMPTY));

        assertThat(Files.readAllBytes(directory.resolve(ACL_PATH))).isEqualTo(bytes("initial ACL"));
        assertThat(storage.load().valueOrFailure("initial ACL").files())
                .containsOnlyKeys(ACL_PATH)
                .containsEntry(ACL_PATH, bytes("initial ACL"));
    }

    @Test
    void overwritesExistingFileWithShorterContent() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.write(root.resolve(ACL_PATH), bytes("previous longer ACL content"));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("updated ACL")),
                new AccessControlSaveRequest("update ACL", UserEmail.EMPTY));

        assertThat(Files.readAllBytes(root.resolve(ACL_PATH))).isEqualTo(bytes("updated ACL"));
        LocalAccessControlStorage reopened = new LocalAccessControlStorage(config(root));
        assertThat(reopened.load().valueOrFailure("updated ACL").files())
                .containsOnlyKeys(ACL_PATH)
                .containsEntry(ACL_PATH, bytes("updated ACL"));
    }

    @Test
    void loadsAndSavesMultipleConfiguredFilesWithConfiguredPrimaryPath() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("roles"));
        Files.write(root.resolve(ACL_PATH), bytes("existing ACL"));
        Files.write(root.resolve(ROLES_PATH), bytes("existing roles"));
        BootstrapConfigurationSourceConfig configuration = config(root);
        configuration.setPaths(List.of(ROLES_PATH, ACL_PATH));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(configuration);

        AccessControlSnapshot loaded = storage.load().valueOrFailure("configured files");

        assertThat(storage.primaryPath()).isEqualTo(ROLES_PATH);
        assertThat(loaded.files()).containsOnlyKeys(ROLES_PATH, ACL_PATH)
                .containsEntry(ROLES_PATH, bytes("existing roles"))
                .containsEntry(ACL_PATH, bytes("existing ACL"));

        storage.save(
                new AccessControlSnapshot(Map.of(
                        ROLES_PATH, bytes("updated roles"),
                        ACL_PATH, bytes("updated ACL")), Optional.empty()),
                new AccessControlSaveRequest("update configured files", UserEmail.EMPTY));

        assertThat(Files.readAllBytes(root.resolve(ROLES_PATH))).isEqualTo(bytes("updated roles"));
        assertThat(Files.readAllBytes(root.resolve(ACL_PATH))).isEqualTo(bytes("updated ACL"));
        assertThat(storage.load().valueOrFailure("updated files").files())
                .containsOnlyKeys(ROLES_PATH, ACL_PATH)
                .containsEntry(ROLES_PATH, bytes("updated roles"))
                .containsEntry(ACL_PATH, bytes("updated ACL"));
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void resolverUsesLocalStorageForFilesystemSources(boolean fileUri, boolean createIfMissing)
            throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("roles"));
        Files.write(root.resolve(ACL_PATH), bytes("resolved ACL"));
        Files.write(root.resolve(ROLES_PATH), bytes("resolved roles"));
        String location = fileUri ? root.toUri().toString() : root.toString();
        ResolvedBootstrapSource source = new ResolvedBootstrapSource(
                BootstrapRepositorySources.CONFIGURATION,
                location,
                Optional.empty(),
                "refs/heads/main",
                List.of(ROLES_PATH, ACL_PATH),
                Optional.empty(),
                createIfMissing);

        AccessControlStorage storage = new AccessControlStorageResolver(
                new BootstrapRepositorySources(List.of(source)),
                new InMemoryNativeGitRepositoryProvider()).resolve();

        assertThat(storage).isInstanceOf(LocalAccessControlStorage.class);
        assertThat(storage.primaryPath()).isEqualTo(ROLES_PATH);
        assertThat(storage.createIfMissing()).isEqualTo(createIfMissing);
        assertThat(storage.load().valueOrFailure("resolved filesystem ACL").files())
                .containsOnlyKeys(ROLES_PATH, ACL_PATH)
                .containsEntry(ROLES_PATH, bytes("resolved roles"))
                .containsEntry(ACL_PATH, bytes("resolved ACL"));
    }

    private static BootstrapConfigurationSourceConfig config(Path directory) {
        BootstrapConfigurationSourceConfig configuration = new BootstrapConfigurationSourceConfig();
        configuration.setLocation(directory.toUri().toString());
        configuration.setPath(ACL_PATH);
        return configuration;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
