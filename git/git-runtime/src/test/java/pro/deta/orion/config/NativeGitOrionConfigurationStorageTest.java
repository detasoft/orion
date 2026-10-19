package pro.deta.orion.config;

import pro.deta.orion.git.fileapi.GitCommitAuthor;

import pro.deta.orion.git.fileapi.GitCommitMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.bootstrap.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.util.Result;

import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Subscription;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import pro.deta.orion.git.nativestorage.RepositoryRefChanged;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitOrionConfigurationStorageTest {
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    private static final String ACL_PATH = "config/orion.xml";

    @Test
    void requiresConfigurationRepositoryBootstrap() {
        assertThatThrownBy(() -> new NativeGitOrionConfigurationStorage(
                new NativeGitRepositoryFactory(
                        pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(EVENTS.manager())),
                new BootstrapConfigurationSourceConfig()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Orion configuration requires a resolved Git repository");
    }

    @Test
    void resolverUsesRepositoryBackedSourceAlias() throws Exception {
        String secondaryPath = "config/roles.xml";
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(EVENTS.manager());
        NativeGitRepository repository = provider.create("bootstrap/proxy-alias")
                .valueOrFailure("create proxy alias");
        repository
                .files()
                .withAccess(
                        "refs/heads/main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "seed"),
                        fileAccess -> {
                            fileAccess.write(ACL_PATH, bytes("resolved acl"));
                            fileAccess.write(secondaryPath, bytes("resolved roles"));
                            fileAccess.apply();
                            return null;
                        });
        OrionVersionedConfigurationStorage storage = new NativeGitOrionConfigurationStorage(
                provider, "bootstrap/proxy-alias", "refs/heads/main", ACL_PATH);

        assertThat(storage.load().result().valueOrFailure("Cannot read configuration document"))
                .isEqualTo(document("resolved acl"));
        storage.save(
                new OrionRevision.Stored(repository.refs().get("refs/heads/main")),
                document("updated ACL"),
                new ChangeMetadata(UserEmail.EMPTY, "update"));
        assertThat(repository.files().readBytes("refs/heads/main", secondaryPath))
                .isEqualTo(bytes("resolved roles"));
        assertThat(repository.files().readBytes("refs/heads/main", ACL_PATH))
                .isEqualTo(bytes("updated ACL"));
    }

    @Test
    void usesOneCanonicalResolvedIdentityForEncodedReadsAndWrites(@TempDir Path root)
            throws Exception {
        NativeGitRepositoryFactory provider = new NativeGitRepositoryFactory(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.file(root, EVENTS.manager()));
        NativeGitRepository selected = provider.provider().create("team/repo")
                .valueOrFailure("selected repository");
        String ref = "refs/heads/configuration";
        selected.files()
                .withAccess(
                        ref,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "seed"),
                        fileAccess -> {
                            fileAccess.write(ACL_PATH, bytes("selected ACL"));
                            fileAccess.apply();
                            return null;
                        });
        BootstrapConfigurationSourceConfig configuration = config();
        configuration.setLocation("local:team%2Frepo");
        configuration.setRef("configuration");
        configuration.setCreateDefaultIfMissing(false);
        provider.resolveProvisional(NativeGitRepositoryFactory.CONFIGURATION_SOURCE, configuration, false);
        assertThat(provider.bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE))
                .contains("team/repo");
        OrionVersionedConfigurationStorage storage =
                new NativeGitOrionConfigurationStorage(provider, configuration);

        OrionDocumentRevision loaded = storage.load();
        assertThat(loaded.result()
                .valueOrFailure("Cannot read configuration document")).isEqualTo(document("selected ACL"));
        storage.save(
                loaded.revision(),
                document("versioned update"),
                new ChangeMetadata(UserEmail.EMPTY, "versioned update"));

        assertThat(selected.files().readBytes(ref, ACL_PATH)).isEqualTo(bytes("versioned update"));
        assertThat(provider.provider().repositoryNames()).containsExactly("team/repo");
    }

    @Test
    void storageDoesNotCreateRepositoryDuringAclStartup() {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(EVENTS.manager());

        assertThatThrownBy(() -> resolvedStorage(provider, ACL_PATH))
                .isInstanceOf(IllegalStateException.class);
        assertThat(provider.repositoryNames()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsConfiguredRepositoryAndCommitsInitialAcl(boolean useDefaults, @TempDir Path rootDirectory) {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(rootDirectory, EVENTS.manager());
        OrionVersionedConfigurationStorage storage = preparedStorage(provider);

        assertThat(storage.load().revision()).isInstanceOf(OrionRevision.Absent.class);
        assertThat(storage.load().result()).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) storage.load().result()).code())
                .isEqualTo(Result.FailureCode.NOT_FOUND);

        OrionRevision.Stored saved = storage.save(
                new OrionRevision.Absent(),
                document("initial acl"),
                new ChangeMetadata(useDefaults ? UserEmail.EMPTY : new UserEmail("root", "root@example.test"),
                        useDefaults ? "" : "bootstrap ACL"));

        OrionDocumentRevision snapshot = storage.load();
        assertThat(snapshot.result()
                .valueOrFailure("Cannot read configuration document")).isEqualTo(document("initial acl"));
        assertThat(snapshot.revision()).isEqualTo(saved);
        assertThat(provider.repositoryNames()).containsExactly("internal/configuration");
        NativeGitRepository repository = provider.find("internal/configuration").valueOrFailure("repository");
        byte[] commit =
                repository
                        .readObject(
                                new ObjectId(saved.value()))
                        .orElseThrow()
                        .data();
        String identity = useDefaults ? "orion <orion@localhost>" : "root <root@example.test>";
        assertThat(new String(commit, StandardCharsets.UTF_8))
                .contains("\nauthor " + identity + " ", "\ncommitter " + identity + " ")
                .endsWith("\n\n" + (useDefaults ? "update files" : "bootstrap ACL") + "\n");
    }

    @Test
    void reusesExistingRepositoryAndConfiguredRefAfterRestart(@TempDir Path rootDirectory) {
        try (NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(rootDirectory, EVENTS.manager())) {
            OrionVersionedConfigurationStorage first = preparedStorage(provider);
            first.save(
                    new OrionRevision.Absent(),
                    document("persisted acl"),
                    new ChangeMetadata(UserEmail.EMPTY, "bootstrap ACL"));
            EVENTS.await();
        }
        try (NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(rootDirectory, EVENTS.manager())) {
            OrionVersionedConfigurationStorage restarted = preparedStorage(provider);
            assertThat(
                            restarted
                                    .load()
                                    .result().valueOrFailure("Cannot read configuration document"))
                    .isEqualTo(document("persisted acl"));
        }
    }

    @Test
    void createsMissingDocumentThroughEditorAtTheExistingStorageRevision(
            @TempDir Path rootDirectory) throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(rootDirectory, EVENTS.manager());
        NativeGitRepository repository = provider.create("internal/configuration")
                .valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "refs/heads/configuration",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "add readme"),
                        fileAccess -> {
                            fileAccess.write("README.md", bytes("not an ACL"));
                            fileAccess.apply();
                            return null;
                        });
        OrionVersionedConfigurationStorage storage = preparedStorage(provider);

        OrionConfigurationEditor editor = editor(storage);
        OrionDocumentRevision read = editor.load();
        Result<OrionDocument> result = read.result();
        assertThat(read.revision())
                .isEqualTo(
                        new OrionRevision.Stored(
                                repository.refs().get("refs/heads/configuration")));

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
        OrionConfigurationModification edit =
                editor.edit(
                        new OrionDocumentRevision(Result.of(document("created ACL")), read.revision()),
                        ChangeMetadata.EMPTY);
        edit.apply();
        assertThat(storage.load().result().valueOrFailure("Cannot read configuration document"))
                .isEqualTo(document("created ACL"));
        assertThat(repository.files().readBytes("refs/heads/configuration", "README.md"))
                .isEqualTo(bytes("not an ACL"));
    }

    @Test
    void rejectsConcurrentCreationAfterEditorReadsAnEmptyVersionedStorage() throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(EVENTS.manager());
        NativeGitRepository repository =
                provider.create("internal/configuration").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "refs/heads/configuration",
                        GitCommitMetadata.EMPTY,
                        access -> {
                            access.write("README.md", bytes("existing file"));
                            access.apply();
                            return null;
                        });
        OrionVersionedConfigurationStorage storage = preparedStorage(provider);
        OrionConfigurationEditor editor = editor(storage);
        OrionDocumentRevision read = editor.load();
        OrionConfigurationModification edit =
                editor.edit(
                        new OrionDocumentRevision(Result.of(document("losing ACL")), read.revision()),
                        ChangeMetadata.EMPTY);
        editor.edit(
                        new OrionDocumentRevision(Result.of(document("winning ACL")), read.revision()),
                        ChangeMetadata.EMPTY)
                .apply();

        assertThatThrownBy(edit::apply)
                .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
        assertThat(storage.load().result().valueOrFailure("Cannot read configuration document"))
                .isEqualTo(document("winning ACL"));
        assertThat(repository.files().readBytes("refs/heads/configuration", "README.md"))
                .isEqualTo(bytes("existing file"));
    }

    private static OrionConfigurationEditor editor(OrionVersionedConfigurationStorage storage) {
        return new OrionConfigurationEditor(
                storage,
                new BootstrapConfiguration(),
                pro.deta.orion.keymaterial.ConfigurationSecretCipher.unavailable(),
                pro.deta.orion.keymaterial.OrionKeyMaterial.unavailable(),
                EVENTS.manager());
    }

    @Test
    void retainsTheStorageRevisionWhenTheDocumentCannotBeDecoded() throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(EVENTS.manager());
        NativeGitRepository repository =
                provider.create("internal/configuration").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "refs/heads/configuration",
                        GitCommitMetadata.EMPTY,
                        access -> {
                            access.write(ACL_PATH, "<broken".getBytes(StandardCharsets.UTF_8));
                            access.apply();
                            return null;
                        });
        OrionConfigurationEditor editor = editor(preparedStorage(provider));
        OrionDocumentRevision read = editor.load();

        assertThat(read.result())
                .isInstanceOfSatisfying(
                        Result.Failure.class,
                        failure ->
                                assertThat(failure.code()).isEqualTo(Result.FailureCode.GENERAL));
        assertThat(read.revision())
                .isEqualTo(
                        new OrionRevision.Stored(
                                repository.refs().get("refs/heads/configuration")));
        assertThatThrownBy(() -> editor.edit(new OrionRevision.Stored("stale"), ChangeMetadata.EMPTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot load configuration for update");
    }

    @Test
    void loadsOnlyTheConfiguredFile(@TempDir Path rootDirectory) throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(rootDirectory, EVENTS.manager());
        NativeGitRepository repository = provider.create("internal/configuration")
                .valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "refs/heads/configuration",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "add primary ACL"),
                        fileAccess -> {
                            fileAccess.write(ACL_PATH, bytes("primary ACL"));
                            fileAccess.apply();
                            return null;
                        });
        OrionVersionedConfigurationStorage storage = resolvedStorage(provider, ACL_PATH);

        Result<OrionDocument> result = storage.load().result();

        assertThat(result.valueOrFailure("configured ACL")).isEqualTo(document("primary ACL"));
    }

    @Test
    void reportsExactSnapshotsOfAcceptedUpdatesEvenAfterTheBranchMoves(@TempDir Path rootDirectory)
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(rootDirectory, EVENTS.manager());
        CountDownLatch processingFirstUpdate = new CountDownLatch(1);
        CountDownLatch resumeProcessing = new CountDownLatch(1);
        AtomicBoolean gateFailed = new AtomicBoolean();
        try (Subscription gate = EVENTS.manager().subscribe(RepositoryRefChanged.class, event -> {
            if (!event.update().ref().value().equals("refs/heads/configuration")) return;
            processingFirstUpdate.countDown();
            try {
                if (!resumeProcessing.await(10, TimeUnit.SECONDS)) gateFailed.set(true);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                gateFailed.set(true);
            }
        })) {
            OrionVersionedConfigurationStorage storage = preparedStorage(provider);
            List<OrionDocumentRevision> changes = new ArrayList<>();
            Subscription subscription = EVENTS.manager().subscribe(ConfigurationChanged.class, event -> {
                if (event.storage() == storage) changes.add(event.snapshot());
            });
            try {
                OrionRevision.Stored first = storage.save(new OrionRevision.Absent(), document("first"),
                        new ChangeMetadata(UserEmail.EMPTY, "first"));
                assertThat(processingFirstUpdate.await(5, TimeUnit.SECONDS)).isTrue();
                provider.find("internal/configuration")
                        .valueOrFailure("repository")
                        .files()
                        .withAccess("refs/heads/other",
                                new GitCommitMetadata(GitCommitAuthor.EMPTY, "other branch"), access -> {
                            access.write(ACL_PATH, bytes("other"));
                            access.apply();
                            return null;
                        });
                OrionRevision.Stored second = storage.save(first, document("second"),
                        new ChangeMetadata(UserEmail.EMPTY, "second"));
                resumeProcessing.countDown();
                EVENTS.await();
                subscription.close();
                storage.save(second, document("third"), new ChangeMetadata(UserEmail.EMPTY, "third"));
                EVENTS.await();

                assertThat(gateFailed).isFalse();
                assertThat(changes).extracting(OrionDocumentRevision::revision).containsExactly(first, second);
                assertThat(changes).extracting(snapshot -> snapshot.result().valueOrFailure("event document"))
                        .containsExactly(document("first"), document("second"));
            } finally {
                resumeProcessing.countDown();
                subscription.close();
            }
        }
    }

    @Test
    void staleVersionedSaveCannotOverwriteWinningAclOrUnrelatedFiles(@TempDir Path rootDirectory) throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(rootDirectory, EVENTS.manager());
        OrionVersionedConfigurationStorage storage = preparedStorage(provider);
        storage.save(
                new OrionRevision.Absent(),
                document("version one"),
                new ChangeMetadata(UserEmail.EMPTY, "version one"));
        OrionDocumentRevision stale = storage.load();
        NativeGitRepository repository = provider.find("internal/configuration").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "refs/heads/configuration",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "version two"),
                        fileAccess -> {
                            fileAccess.write(ACL_PATH, bytes("version two"));
                            fileAccess.write("winner.txt", bytes("winner"));
                            fileAccess.apply();
                            return null;
                        });
        String winningVersion = repository.refs().get("refs/heads/configuration");

        assertThatThrownBy(
                        () ->
                                storage.save(
                                        stale.revision(),
                                        document("stale"),
                                        new ChangeMetadata(UserEmail.EMPTY, "stale")))
                .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);

        assertThat(repository.refs().get("refs/heads/configuration")).isEqualTo(winningVersion);
        assertThat(repository.files().readBytes("refs/heads/configuration", ACL_PATH))
                .isEqualTo(bytes("version two"));
        assertThat(repository.files().readBytes("refs/heads/configuration", "winner.txt"))
                .isEqualTo(bytes("winner"));
    }

    @Test
    void createsMissingConfigurationAtExistingRevisionKeepingOtherFiles(@TempDir Path rootDirectory)
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(rootDirectory, EVENTS.manager());
        NativeGitRepository repository = provider.create("internal/configuration").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "refs/heads/configuration",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "winner"),
                        fileAccess -> {
                            fileAccess.write("winner.txt", bytes("winner"));
                            fileAccess.apply();
                            return null;
                        });
        OrionVersionedConfigurationStorage storage = resolvedStorage(provider, ACL_PATH);

        storage.save(
                new OrionRevision.Stored(repository.refs().get("refs/heads/configuration")),
                document("created"),
                new ChangeMetadata(UserEmail.EMPTY, "created"));

        assertThat(repository.files().readBytes("refs/heads/configuration", ACL_PATH))
                .isEqualTo(bytes("created"));
        assertThat(repository.files().readBytes("refs/heads/configuration", "winner.txt"))
                .isEqualTo(bytes("winner"));
    }

    @Test
    void failsWhenTheStorageRevisionCannotBeDetermined() {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(EVENTS.manager());
        OrionVersionedConfigurationStorage storage = preparedStorage(provider);
        provider.close();

        assertThatThrownBy(storage::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Repository provider is closed");
    }

    @Test
    void loadsThroughProviderReadOperation(@TempDir Path rootDirectory) throws Exception {
        RecordingProvider provider =
                new RecordingProvider(
                        pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.file(
                                rootDirectory, EVENTS.manager()));
        OrionVersionedConfigurationStorage storage = preparedStorage(provider);
        provider.find("internal/configuration")
                .valueOrFailure("repository")
                .files()
                .withAccess(
                        "refs/heads/configuration",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "seed"),
                        fileAccess -> {
                            fileAccess.write(ACL_PATH, bytes("provider acl"));
                            fileAccess.apply();
                            return null;
                        });

        OrionDocumentRevision snapshot = storage.load();

        assertThat(provider.reads).hasValue(2);
        assertThat(snapshot.result()
                .valueOrFailure("Cannot read configuration document")).isEqualTo(document("provider acl"));
    }

    @Test
    void savesConfiguredRefThroughProviderOperation(@TempDir Path rootDirectory) {
        RecordingProvider provider =
                new RecordingProvider(
                        pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.file(
                                rootDirectory, EVENTS.manager()));
        OrionVersionedConfigurationStorage storage = preparedStorage(provider);

        storage.save(
                new OrionRevision.Absent(),
                document("provider acl"),
                new ChangeMetadata(UserEmail.EMPTY, "provider ACL"));

        assertThat(provider.saves).hasValue(1);
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

    private static OrionVersionedConfigurationStorage preparedStorage(
            NativeGitRepositoryProvider provider) {
        if (!provider.exists("internal/configuration")) {
            provider.create("internal/configuration").valueOrFailure("repository");
        }
        return resolvedStorage(provider, ACL_PATH);
    }

    private static OrionVersionedConfigurationStorage resolvedStorage(
            NativeGitRepositoryProvider provider, String path) {
        EVENTS.own(provider);
        NativeGitOrionConfigurationStorage storage = new NativeGitOrionConfigurationStorage(provider,
                "internal/configuration", "refs/heads/configuration", path);
        EVENTS.own(storage);
        return storage;
    }

    private static OrionDocument document(String name) {
        OrionDocument.Organization organization = new OrionDocument.Organization(new OrganizationId("fixture"),
                name, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl()), List.of(organization));
    }

    private static byte[] bytes(String value) {
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            OrionXml.write(document(value), output);
            return output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class RecordingProvider extends NativeGitRepositoryProvider {
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger saves = new AtomicInteger();

        private RecordingProvider(pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend backend) {
            super(backend);
        }

        @Override
        public Result<NativeGitRepository> openForRead(String repositoryName) {
            reads.incrementAndGet();
            return super.openForRead(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> openForWrite(String repositoryName) {
            saves.incrementAndGet();
            return super.openForWrite(repositoryName);
        }
    }
}
