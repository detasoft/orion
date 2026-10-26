package pro.deta.orion;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import pro.deta.orion.git.fileapi.GitCommitAuthor;

import pro.deta.orion.git.fileapi.GitCommitMetadata;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.keymaterial.KeyMaterialSnapshot;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.fileapi.GitFileEvents;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import pro.deta.orion.keymaterial.KeyMaterialStoreConflictException;
import pro.deta.orion.bootstrap.config.BootstrapSourceConfig;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitKeyMaterialContentStoreTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    private static final String REPOSITORY = "orion";
    private static final String REF = "refs/heads/main";
    private static final String MATERIAL_PATH = "material.p12";

    @TempDir
    private Path tempDir;

    @Test
    void allowsReadingMaterialWhileARefChangeIsBeingPublished() throws Exception {
        java.util.concurrent.atomic.AtomicReference<NativeGitKeyMaterialContentStore> activeStore =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            pro.deta.orion.event.OrionEventManager events = new pro.deta.orion.event.OrionEventManager() {
                @Override
                public void publish(pro.deta.orion.event.type.OrionEvent event) {
                    if (event instanceof pro.deta.orion.git.nativestorage.RepositoryRefChanged
                            && activeStore.get() != null) {
                        try {
                            executor.submit(() -> activeStore.get().read()).get(2,
                                    java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new AssertionError("Store read is blocked by ref event publication", failure);
                        }
                    }
                    super.publish(event);
                }
            };
            events.onStart();
            try (NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(events)) {
                provider.create(REPOSITORY).valueOrFailure("create repository");
                try (NativeGitKeyMaterialContentStore store =
                        new NativeGitKeyMaterialContentStore(provider, REPOSITORY, REF, MATERIAL_PATH)) {
                    activeStore.set(store);
                    String saved = store.write(bytes("material"), null);
                    assertThat(store.read().orElseThrow().version()).isEqualTo(saved);
                }
            } finally {
                events.onStop();
            }
        }
    }

    @Test
    void createsMissingMaterialOnExistingBranchWithoutReplacingConfiguration() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("configuration")));
        NativeGitKeyMaterialContentStore store = fixture.store();

        assertThat(store.read()).isEmpty();
        String version = store.write(bytes("encrypted-material"), null);

        assertThat(store.read().orElseThrow().version()).isEqualTo(version);
        assertThat(fixture.repository().files().readBytes(REF, "orion.xml")).isEqualTo(bytes("configuration"));
        assertThat(fixture.repository().files().readBytes(REF, MATERIAL_PATH))
                .isEqualTo(bytes("encrypted-material"));
        assertThatThrownBy(() -> store.write(bytes("unexpected replacement"), null))
                .isInstanceOf(KeyMaterialStoreConflictException.class);
    }

    @Test
    void preservesUnrelatedConfigurationChangesWithoutReloadingMaterial() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial"),
                "orion.xml", bytes("initial config")));
        NativeGitKeyMaterialContentStore store = fixture.store();
        String version = store.read().orElseThrow().version();
        fixture.repository()
                .files()
                .withAccess(
                        REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "configuration update"),
                        fileAccess -> {
                            fileAccess.write("orion.xml", bytes("new config"));
                            fileAccess.apply();
                            return null;
                        });

        String saved = store.write(bytes("updated material"), version);

        assertThat(store.read().orElseThrow().version()).isEqualTo(saved);
        assertThat(fixture.repository().files().readBytes(REF, "orion.xml")).isEqualTo(bytes("new config"));
        assertThat(fixture.repository().files().readBytes(REF, MATERIAL_PATH))
                .isEqualTo(bytes("updated material"));
    }

    @Test
    void mapsAStaleProviderPublicationToMaterialStoreConflict() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial")));
        NativeGitKeyMaterialContentStore first = fixture.store();
        NativeGitKeyMaterialContentStore second = fixture.store();
        KeyMaterialSnapshot firstSnapshot = first.read().orElseThrow();
        KeyMaterialSnapshot secondSnapshot = second.read().orElseThrow();

        first.write(bytes("first"), firstSnapshot.version());

        assertThatThrownBy(() -> second.write(bytes("second"), secondSnapshot.version()))
                .isInstanceOf(KeyMaterialStoreConflictException.class)
                .hasMessage("Key material store changed before save");
        assertThat(first.read().orElseThrow().bytes()).isEqualTo(bytes("first"));
    }

    @Test
    void rejectsAnUpdateCommittedBetweenPreparationAndPublication() throws Exception {
        NativeGitRepositoryProvider backend = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository = backend.create(REPOSITORY).valueOrFailure("create repository");
        repository
                .files()
                .withAccess(
                        REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "seed repository"),
                        fileAccess -> {
                            fileAccess.write(MATERIAL_PATH, bytes("initial"));
                            fileAccess.apply();
                            return null;
                        });
        InterleavingProvider provider =
                new InterleavingProvider(
                        backend,
                        () -> {
                            try {
                                backend.openForWrite(REPOSITORY)
                                        .valueOrFailure("repository")
                                        .files()
                                        .withAccess(
                                                REF,
                                                new GitCommitMetadata(
                                                        GitCommitAuthor.EMPTY, "concurrent update"),
                                                fileAccess -> {
                                                    fileAccess.write("orion.xml", bytes("concurrent configuration"));
                                                    fileAccess.write(MATERIAL_PATH, bytes("concurrent material"));
                                                    fileAccess.apply();
                                                    return null;
                                                });
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        NativeGitKeyMaterialContentStore store = new NativeGitKeyMaterialContentStore(
                provider, REPOSITORY, REF, MATERIAL_PATH);
        String observedVersion = store.read().orElseThrow().version();

        assertThatThrownBy(() -> store.write(bytes("stale material"), observedVersion))
                .isInstanceOf(KeyMaterialStoreConflictException.class);

        assertThat(repository.files().readBytes(REF, MATERIAL_PATH)).isEqualTo(bytes("concurrent material"));
        assertThat(repository.files().readBytes(REF, "orion.xml")).isEqualTo(bytes("concurrent configuration"));
    }

    @Test
    void publishesMissingMaterialThroughRemoteProviderAndPreservesConfiguration() throws Exception {
        Path worktree = tempDir.resolve("worktree");
        Path bare = tempDir.resolve("upstream.git");
        Git git = Git.init().setDirectory(worktree.toFile()).setInitialBranch("main").call();
        Files.write(worktree.resolve("orion.xml"), bytes("configuration"));
        git.add().addFilepattern("orion.xml").call();
        git.commit().setMessage("configuration").setAuthor("Test", "test@example.invalid").call();
        try (Git ignored = Git.cloneRepository()
                .setURI(worktree.toUri().toString())
                .setDirectory(bare.toFile())
                .setBare(true)
                .call()) {
            // Bare fixture is ready.
        }

        NativeGitRepositoryFactory factory = new NativeGitRepositoryFactory(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events()));
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation("git+" + bare.toUri());
        source.setRef(REF);
        source.setPath(MATERIAL_PATH);
        String repositoryName = factory.resolveProvisional("material", source, true).orElseThrow();
        NativeGitKeyMaterialContentStore store = new NativeGitKeyMaterialContentStore(
                factory.provider(),
                repositoryName,
                NativeGitRepositoryFactory.sourceRefName(source),
                NativeGitRepositoryFactory.repositoryPath(source.getPath()));

        assertThat(store.read()).isEmpty();
        store.write(bytes("encrypted-material"), null);

        Path checkout = tempDir.resolve("checkout");
        try (Git ignored = Git.cloneRepository()
                .setURI(bare.toUri().toString())
                .setDirectory(checkout.toFile())
                .call()) {
            assertThat(Files.readAllBytes(checkout.resolve("orion.xml"))).isEqualTo(bytes("configuration"));
            assertThat(Files.readAllBytes(checkout.resolve(MATERIAL_PATH)))
                    .isEqualTo(bytes("encrypted-material"));
        } finally {
            git.close();
        }
    }

    @Test
    void reportsOnlyFileChangesAndReadsTheirExactCommits() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial")));
        try (NativeGitKeyMaterialContentStore store = fixture.store()) {
            testRuntime.awaitEvents();
            List<String> revisions = new CopyOnWriteArrayList<>();
            GitFile file = new GitFile(fixture.provider(), REPOSITORY, REF, MATERIAL_PATH);
            testRuntime.own(new GitFileEvents(file, revision -> revisions.add(revision.toHex())));
            fixture.repository().files().withAccess(REF,
                    new GitCommitMetadata(GitCommitAuthor.EMPTY, "unrelated file"), access -> {
                        access.write("orion.xml", bytes("updated configuration"));
                        access.apply();
                        return null;
                    });
            fixture.repository().files().withAccess("refs/heads/other",
                    new GitCommitMetadata(GitCommitAuthor.EMPTY, "other branch"), access -> {
                        access.write(MATERIAL_PATH, bytes("other branch"));
                        access.apply();
                        return null;
                    });
            testRuntime.awaitEvents();
            assertThat(revisions).isEmpty();
            String first = store.write(bytes("first"), store.read().orElseThrow().version());
            String second = store.write(bytes("second"), first);
            testRuntime.awaitEvents();
            assertThat(revisions).containsExactly(first, second);
            assertThat(store.read(first).orElseThrow().bytes()).isEqualTo(bytes("first"));
            assertThat(store.read(second).orElseThrow().bytes()).isEqualTo(bytes("second"));
        }
    }

    @Test
    void retainsRunningMaterialAfterAnExternalUpdateAndLoadsItWhenReopened() throws Exception {
        char[] password = "test-password".toCharArray();
        InMemoryKeyMaterialContentStore external = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor original = new KeyMaterialDescriptor("original",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor local = new KeyMaterialDescriptor("local",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor added = new KeyMaterialDescriptor("added",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(password);
             OrionKeyMaterial writer = OrionKeyMaterial.open(external, options)) {
            writer.withModification(modification -> {
                modification.generateKey(original, 2048);
                modification.apply();
            });
            Fixture fixture = fixture(Map.of(MATERIAL_PATH, external.read().orElseThrow().bytes()));
            Logger logger = (Logger) LoggerFactory.getLogger(NativeGitKeyMaterialContentStore.class);
            ListAppender<ILoggingEvent> warnings = new ListAppender<>();
            warnings.start();
            logger.addAppender(warnings);
            try (OrionKeyMaterial running = OrionKeyMaterial.open(fixture.store(), options, testRuntime.events())) {
                running.withModification(modification -> {
                    modification.generateKey(local, 2048);
                    modification.apply();
                });
                testRuntime.awaitEvents();
                assertThat(warnings.list).isEmpty();
                writer.withModification(modification -> {
                    modification.generateKey(added, 2048);
                    modification.apply();
                });
                fixture.repository().files().withAccess(REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "external KMS update"), access -> {
                            access.write(MATERIAL_PATH, external.read().orElseThrow().bytes());
                            access.apply();
                            return null;
                        });
                testRuntime.awaitEvents();
                assertThat(running.contains(original)).isTrue();
                assertThat(running.contains(added)).isFalse();
                assertThat(running.contains(local)).isTrue();
                assertThat(running.version()).isEqualTo(2);
                assertThat(warnings.list).extracting(ILoggingEvent::getFormattedMessage)
                        .anyMatch(message -> message.contains("restart Orion"));
                try (OrionKeyMaterial reopened = OrionKeyMaterial.open(fixture.store(), options)) {
                    assertThat(reopened.contains(original)).isTrue();
                    assertThat(reopened.contains(added)).isTrue();
                    assertThat(reopened.contains(local)).isFalse();
                    assertThat(reopened.version()).isEqualTo(2);
                }
            } finally {
                logger.detachAppender(warnings);
                warnings.stop();
            }
        }
    }

    private Fixture fixture(Map<String, byte[]> initialFiles) throws Exception {
        NativeGitRepositoryFactory factory = new NativeGitRepositoryFactory(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events()));
        NativeGitRepository repository = factory.provider().create(REPOSITORY).valueOrFailure("create repository");
        repository
                .files()
                .withAccess(
                        REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "seed repository"),
                        fileAccess -> {
                            for (Map.Entry<String, byte[]> fileEntry : initialFiles.entrySet()) {
                                fileAccess.write(fileEntry.getKey(), fileEntry.getValue());
                            }
                            fileAccess.apply();
                            return null;
                        });
        return new Fixture(repository, factory.provider());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
            NativeGitRepository repository,
            NativeGitRepositoryProvider provider) {
        private NativeGitKeyMaterialContentStore store() {
            return new NativeGitKeyMaterialContentStore(provider, REPOSITORY, REF, MATERIAL_PATH);
        }
    }

    private final class InterleavingProvider extends NativeGitRepositoryProvider {
        @Override
        public void close() {
            delegate.close();
        }

        private final NativeGitRepositoryProvider delegate;
        private final Runnable beforePublish;
        private boolean interleaved;

        private InterleavingProvider(NativeGitRepositoryProvider delegate, Runnable beforePublish) {
            super(pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events()));
            this.delegate = delegate;
            this.beforePublish = beforePublish;
        }

        @Override
        public NativeGitRepository retained(String repositoryName) {
            return delegate.retained(repositoryName);
        }

        @Override
        public boolean exists(String repositoryName) {
            return delegate.exists(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> find(String repositoryName) {
            return delegate.find(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> create(String repositoryName) {
            return delegate.create(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> openForWrite(String repositoryName) {
            if (!interleaved) {
                interleaved = true;
                beforePublish.run();
            }
            return delegate.openForWrite(repositoryName);
        }
    }
}
