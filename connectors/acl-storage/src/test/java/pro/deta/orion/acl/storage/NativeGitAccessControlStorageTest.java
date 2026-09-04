package pro.deta.orion.acl.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitAccessControlStorageTest {
    private static final String ACL_PATH = "config/orion.xml";

    @Test
    void createsConfiguredRepositoryAndCommitsInitialAcl(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitAccessControlStorage storage = new NativeGitAccessControlStorage(config(), provider);

        assertThat(storage.load()).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) storage.load()).code()).isEqualTo(Result.FailureCode.NOT_FOUND);

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("initial acl")),
                new AccessControlSaveRequest("bootstrap ACL", new UserEmail("root", "root@example.test")));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load");
        assertThat(snapshot.files()).containsOnlyKeys(ACL_PATH);
        assertThat(snapshot.files().get(ACL_PATH)).isEqualTo(bytes("initial acl"));
        assertThat(snapshot.version()).isPresent();
        assertThat(provider.repositoryNames()).containsExactly("internal/configuration");
    }

    @Test
    void reusesExistingRepositoryAndConfiguredRefAfterRestart(@TempDir Path rootDirectory) {
        NativeGitAccessControlStorage first = new NativeGitAccessControlStorage(
                config(),
                new FileNativeGitRepositoryProvider(rootDirectory));
        first.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("persisted acl")),
                new AccessControlSaveRequest("bootstrap ACL", UserEmail.EMPTY));

        NativeGitAccessControlStorage restarted = new NativeGitAccessControlStorage(
                config(),
                new FileNativeGitRepositoryProvider(rootDirectory));

        assertThat(restarted.load().valueOrFailure("ACL should survive restart").files().get(ACL_PATH))
                .isEqualTo(bytes("persisted acl"));
    }

    @Test
    void existingRefWithoutConfiguredAclIsInvalidRatherThanEmpty(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("internal/configuration")
                .valueOrFailure("repository");
        repository.saveFiles(
                "refs/heads/configuration",
                Map.of("README.md", bytes("not an ACL")),
                "add readme",
                GitCommitAuthor.EMPTY);
        NativeGitAccessControlStorage storage = new NativeGitAccessControlStorage(config(), provider);

        Result<AccessControlSnapshot> result = storage.load();

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.GENERAL);
    }

    @Test
    void reportsOnlyAcceptedUpdatesToConfiguredRef(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitAccessControlStorage storage = new NativeGitAccessControlStorage(config(), provider);
        AtomicInteger changes = new AtomicInteger();
        AccessControlStorage.ChangeSubscription subscription = storage.onChange(ignored -> changes.incrementAndGet());

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("first")),
                new AccessControlSaveRequest("first", UserEmail.EMPTY));
        provider.find("internal/configuration").valueOrFailure("repository").saveFiles(
                "refs/heads/other",
                Map.of(ACL_PATH, bytes("other")),
                "other branch",
                GitCommitAuthor.EMPTY);
        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("second")),
                new AccessControlSaveRequest("second", UserEmail.EMPTY));
        subscription.close();
        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("third")),
                new AccessControlSaveRequest("third", UserEmail.EMPTY));

        assertThat(changes).hasValue(2);
    }

    @Test
    void staleVersionedSaveCannotOverwriteWinningAclOrUnrelatedFiles(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitAccessControlStorage storage = new NativeGitAccessControlStorage(config(), provider);
        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("version one")),
                new AccessControlSaveRequest("version one", UserEmail.EMPTY));
        AccessControlSnapshot stale = storage.load().valueOrFailure("version one");
        NativeGitRepository repository = provider.find("internal/configuration").valueOrFailure("repository");
        repository.saveFiles(
                "refs/heads/configuration",
                Map.of(ACL_PATH, bytes("version two"), "winner.txt", bytes("winner")),
                "version two",
                GitCommitAuthor.EMPTY);
        String winningVersion = repository.refs().get("refs/heads/configuration");

        assertThatThrownBy(() -> storage.save(
                stale,
                new AccessControlSaveRequest("stale", UserEmail.EMPTY)))
                .isInstanceOf(AccessControlConcurrentUpdateException.class);

        assertThat(repository.refs().get("refs/heads/configuration")).isEqualTo(winningVersion);
        assertThat(repository.loadFiles(
                "refs/heads/configuration",
                List.of(ACL_PATH, "winner.txt")).files())
                .containsEntry(ACL_PATH, bytes("version two"))
                .containsEntry("winner.txt", bytes("winner"));
    }

    @Test
    void versionlessSaveRetainsUnconditionalInitialCreationPath(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("internal/configuration").valueOrFailure("repository");
        repository.saveFiles(
                "refs/heads/configuration",
                Map.of("winner.txt", bytes("winner")),
                "winner",
                GitCommitAuthor.EMPTY);
        NativeGitAccessControlStorage storage = new NativeGitAccessControlStorage(config(), provider);

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("created")),
                new AccessControlSaveRequest("created", UserEmail.EMPTY));

        assertThat(repository.loadFiles(
                "refs/heads/configuration",
                List.of(ACL_PATH, "winner.txt")).files())
                .containsEntry(ACL_PATH, bytes("created"))
                .containsEntry("winner.txt", bytes("winner"));
    }

    private static OrionConfiguration.BootstrapAccessControlConfig config() {
        OrionConfiguration.BootstrapAccessControlConfig config =
                new OrionConfiguration.BootstrapAccessControlConfig();
        config.setLocation("local:internal/configuration");
        config.setRef("refs/heads/configuration");
        config.setPaths(List.of(ACL_PATH));
        return config;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
