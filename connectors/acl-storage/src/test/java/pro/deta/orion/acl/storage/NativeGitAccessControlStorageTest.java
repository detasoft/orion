package pro.deta.orion.acl.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitAccessControlStorageTest {
    private static final String ACL_PATH = "config/orion.xml";

    @Test
    void resolverRequiresConfigurationRepositoryBootstrap() {
        assertThatThrownBy(() -> new AccessControlStorageResolver(
                new BootstrapRepositorySources(List.of()),
                new pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider()).resolve())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap source has not been resolved: configuration");
    }

    @Test
    void resolverUsesRepositoryBackedSourceAlias() throws Exception {
        String secondaryPath = "config/roles.xml";
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("bootstrap/proxy-alias")
                .valueOrFailure("create proxy alias");
        repository.saveFiles(
                "refs/heads/main",
                Map.of(
                        ACL_PATH, bytes("resolved acl"),
                        secondaryPath, bytes("resolved roles")),
                "seed",
                GitCommitAuthor.EMPTY);
        ResolvedBootstrapSource source = new ResolvedBootstrapSource(
                BootstrapRepositorySources.CONFIGURATION,
                "git+https://sensitive.example/private.git",
                Optional.of("bootstrap/proxy-alias"),
                "refs/heads/main",
                List.of(ACL_PATH, secondaryPath),
                Optional.of(repository.refs().get("refs/heads/main")),
                false);

        AccessControlStorage storage = new AccessControlStorageResolver(
                new BootstrapRepositorySources(List.of(source)),
                provider).resolve();

        assertThat(storage).isInstanceOf(NativeGitAccessControlStorage.class);
        assertThat(storage.primaryPath()).isEqualTo(ACL_PATH);
        assertThat(storage.createIfMissing()).isFalse();
        assertThat(storage.load().valueOrFailure("resolved ACL").files())
                .containsEntry(ACL_PATH, bytes("resolved acl"))
                .containsEntry(secondaryPath, bytes("resolved roles"));
    }

    @Test
    void usesOneCanonicalResolvedIdentityForEncodedReadsAndWrites(@TempDir Path root)
            throws Exception {
        FileNativeGitRepositoryProvider backend = new FileNativeGitRepositoryProvider(root);
        NativeGitRepository selected = backend.create("team/repo").valueOrFailure("selected repository");
        String ref = "refs/heads/configuration";
        selected.saveFiles(ref, Map.of(ACL_PATH, bytes("selected ACL")), "seed", GitCommitAuthor.EMPTY);
        BootstrapConfigurationSourceConfig configuration = config();
        configuration.setLocation("local:team%2Frepo");
        configuration.setRef("configuration");
        configuration.setCreateDefaultIfMissing(false);
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(backend);
        ResolvedBootstrapSource source = provider.resolveProvisional(
                BootstrapRepositorySources.CONFIGURATION, configuration, false);
        assertThat(source.repositoryName()).contains("team/repo");
        AccessControlStorage storage = new AccessControlStorageResolver(
                new BootstrapRepositorySources(List.of(source)), provider).resolve();

        AccessControlSnapshot loaded = storage.load().valueOrFailure("selected ACL");
        assertThat(loaded.files())
                .containsEntry(ACL_PATH, bytes("selected ACL"));
        assertThat(storage.primaryPath()).isEqualTo(ACL_PATH);
        assertThat(storage.createIfMissing()).isFalse();
        storage.save(
                new AccessControlSnapshot(Map.of(ACL_PATH, bytes("versioned update")), loaded.version()),
                new AccessControlSaveRequest("versioned update", UserEmail.EMPTY));

        assertThat(selected.loadFiles(ref, List.of(ACL_PATH)).files())
                .containsEntry(ACL_PATH, bytes("versioned update"));
        assertThat(backend.repositoryNames()).containsExactly("team/repo");
    }

    @Test
    void storageDoesNotCreateRepositoryDuringAclStartup() {
        NativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();

        resolvedStorage(provider, List.of(ACL_PATH));

        assertThat(provider.repositoryNames()).isEmpty();
    }

    @Test
    void createsConfiguredRepositoryAndCommitsInitialAcl(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        AccessControlStorage storage = preparedStorage(provider);

        assertThat(storage.load()).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) storage.load()).code()).isEqualTo(Result.FailureCode.NOT_FOUND);

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("initial acl")),
                new AccessControlSaveRequest("bootstrap ACL", new UserEmail("root", "root@example.test")));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load");
        assertThat(snapshot.files()).containsOnlyKeys(ACL_PATH);
        assertThat(snapshot.files().get(ACL_PATH)).isEqualTo(bytes("initial acl"));
        assertThat(snapshot.version()).isPresent();
        assertThat(storage.createIfMissing()).isTrue();
        assertThat(provider.repositoryNames()).containsExactly("internal/configuration");
    }

    @Test
    void reusesExistingRepositoryAndConfiguredRefAfterRestart(@TempDir Path rootDirectory) {
        AccessControlStorage first = preparedStorage(
                new FileNativeGitRepositoryProvider(rootDirectory));
        first.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("persisted acl")),
                new AccessControlSaveRequest("bootstrap ACL", UserEmail.EMPTY));

        AccessControlStorage restarted = preparedStorage(
                new FileNativeGitRepositoryProvider(rootDirectory));

        assertThat(restarted.load().valueOrFailure("ACL should survive restart").files().get(ACL_PATH))
                .isEqualTo(bytes("persisted acl"));
    }

    @Test
    void existingRefWithoutPrimaryAclIsReportedAsMissing(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("internal/configuration")
                .valueOrFailure("repository");
        repository.saveFiles(
                "refs/heads/configuration",
                Map.of("README.md", bytes("not an ACL")),
                "add readme",
                GitCommitAuthor.EMPTY);
        AccessControlStorage storage = preparedStorage(provider);

        Result<AccessControlSnapshot> result = storage.load();

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
    }

    @Test
    void existingRefWithoutSecondaryAclIsInvalid(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("internal/configuration")
                .valueOrFailure("repository");
        repository.saveFiles(
                "refs/heads/configuration",
                Map.of(ACL_PATH, bytes("primary ACL")),
                "add primary ACL",
                GitCommitAuthor.EMPTY);
        AccessControlStorage storage = resolvedStorage(provider, List.of(ACL_PATH, "config/roles.xml"));

        Result<AccessControlSnapshot> result = storage.load();

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.GENERAL);
    }

    @Test
    void reportsOnlyAcceptedUpdatesToConfiguredRef(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(rootDirectory);
        AccessControlStorage storage = preparedStorage(provider);
        AtomicInteger changes = new AtomicInteger();
        AccessControlStorage.ChangeSubscription subscription = storage.onChange(
                ignored -> changes.incrementAndGet());

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
        AccessControlStorage storage = preparedStorage(provider);
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
        AccessControlStorage storage = resolvedStorage(provider, List.of(ACL_PATH));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("created")),
                new AccessControlSaveRequest("created", UserEmail.EMPTY));

        assertThat(repository.loadFiles(
                "refs/heads/configuration",
                List.of(ACL_PATH, "winner.txt")).files())
                .containsEntry(ACL_PATH, bytes("created"))
                .containsEntry("winner.txt", bytes("winner"));
    }

    @Test
    void loadsThroughProviderReadOperation(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider backend = new FileNativeGitRepositoryProvider(rootDirectory);
        RecordingProvider provider = new RecordingProvider(backend);
        AccessControlStorage storage = preparedStorage(provider);
        backend.find("internal/configuration").valueOrFailure("repository").saveFiles(
                "refs/heads/configuration",
                Map.of(ACL_PATH, bytes("provider acl")),
                "seed",
                GitCommitAuthor.EMPTY);

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load through provider");

        assertThat(provider.reads).hasValue(1);
        assertThat(snapshot.files().get(ACL_PATH)).isEqualTo(bytes("provider acl"));
    }

    @Test
    void savesConfiguredRefThroughProviderOperation(@TempDir Path rootDirectory) {
        RecordingProvider provider = new RecordingProvider(
                new FileNativeGitRepositoryProvider(rootDirectory));
        AccessControlStorage storage = preparedStorage(provider);

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("provider acl")),
                new AccessControlSaveRequest("provider ACL", UserEmail.EMPTY));

        assertThat(provider.saves).hasValue(1);
        assertThat(provider.savedRef).isEqualTo("refs/heads/configuration");
        assertThat(provider.find("internal/configuration").valueOrFailure("repository").refs())
                .containsKey("refs/heads/configuration");
    }

    private static BootstrapConfigurationSourceConfig config() {
        BootstrapConfigurationSourceConfig config = new BootstrapConfigurationSourceConfig();
        config.setLocation("local:internal/configuration");
        config.setRef("refs/heads/configuration");
        config.setPath(ACL_PATH);
        return config;
    }

    private static AccessControlStorage preparedStorage(
            NativeGitRepositoryProvider provider) {
        if (!provider.exists("internal/configuration")) {
            provider.create("internal/configuration").valueOrFailure("repository");
        }
        return resolvedStorage(provider, List.of(ACL_PATH));
    }

    private static AccessControlStorage resolvedStorage(
            NativeGitRepositoryProvider provider, List<String> paths) {
        ResolvedBootstrapSource source = new ResolvedBootstrapSource(
                BootstrapRepositorySources.CONFIGURATION,
                "local:internal/configuration",
                Optional.of("internal/configuration"),
                "refs/heads/configuration",
                paths,
                Optional.empty(),
                true);
        return new AccessControlStorageResolver(new BootstrapRepositorySources(List.of(source)), provider)
                .resolve();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingProvider implements NativeGitRepositoryProvider {
        private final NativeGitRepositoryProvider backend;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger saves = new AtomicInteger();
        private String savedRef;

        private RecordingProvider(NativeGitRepositoryProvider backend) {
            this.backend = backend;
        }

        @Override
        public List<String> repositoryNames() {
            return backend.repositoryNames();
        }

        @Override
        public boolean exists(String repositoryName) {
            return backend.exists(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> find(String repositoryName) {
            return backend.find(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> create(String repositoryName) {
            return backend.create(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> openForRead(String repositoryName) {
            reads.incrementAndGet();
            return backend.find(repositoryName);
        }

        @Override
        public void saveFiles(
                String repositoryName,
                String refName,
                Map<String, byte[]> files,
                String message,
                GitCommitAuthor author) throws pro.deta.orion.git.nativestorage.GitOperationException {
            saves.incrementAndGet();
            savedRef = refName;
            NativeGitRepositoryProvider.super.saveFiles(repositoryName, refName, files, message, author);
        }
    }
}
