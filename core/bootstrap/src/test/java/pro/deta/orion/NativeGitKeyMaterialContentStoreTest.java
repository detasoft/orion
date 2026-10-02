package pro.deta.orion;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.keymaterial.KeyMaterialSnapshot;
import pro.deta.orion.keymaterial.KeyMaterialStoreConflictException;
import pro.deta.orion.bootstrap.config.BootstrapSourceConfig;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitKeyMaterialContentStoreTest {
    private static final String REPOSITORY = "orion";
    private static final String REF = "refs/heads/main";
    private static final String MATERIAL_PATH = "material.p12";

    @TempDir
    private Path tempDir;

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
    }

    @Test
    void preservesAConfigurationEditMadeAfterMaterialWasOpened() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial"),
                "orion.xml", bytes("initial config")));
        NativeGitKeyMaterialContentStore store = fixture.store();
        String version = store.read().orElseThrow().version();
        fixture.repository().files().withAccess(REF, "configuration update", GitCommitAuthor.EMPTY,
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
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = backend.create(REPOSITORY).valueOrFailure("create repository");
        repository.files().withAccess(REF, "seed repository", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write(MATERIAL_PATH, bytes("initial"));
            fileAccess.apply();
            return null;
        });
        InterleavingProvider provider = new InterleavingProvider(backend, () -> {
            try {
                backend.openForWrite(REPOSITORY).valueOrFailure("repository").files().withAccess(REF,
                        "concurrent update", GitCommitAuthor.EMPTY, fileAccess -> {
                    fileAccess.write("orion.xml", bytes("concurrent configuration"));
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

        assertThat(repository.files().readBytes(REF, MATERIAL_PATH)).isEqualTo(bytes("initial"));
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

        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(backend);
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation("git+" + bare.toUri());
        source.setRef(REF);
        source.setPath(MATERIAL_PATH);
        ResolvedBootstrapSource resolved = provider.resolveProvisional("material", source, true);
        NativeGitKeyMaterialContentStore store = new NativeGitKeyMaterialContentStore(
                provider,
                resolved.repositoryName().orElseThrow(),
                resolved.refName(),
                resolved.path());

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

    private static Fixture fixture(Map<String, byte[]> initialFiles) throws Exception {
        NativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = backend.create(REPOSITORY).valueOrFailure("create repository");
        repository.files().withAccess(REF, "seed repository", GitCommitAuthor.EMPTY, fileAccess -> {
            for (Map.Entry<String, byte[]> fileEntry : initialFiles.entrySet()) {
                fileAccess.write(fileEntry.getKey(), fileEntry.getValue());
            }
            fileAccess.apply();
            return null;
        });
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(backend);
        return new Fixture(repository, provider);
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

    private static final class InterleavingProvider implements NativeGitRepositoryProvider {
        @Override
        public void close() {
            delegate.close();
        }

        private final NativeGitRepositoryProvider delegate;
        private final Runnable beforePublish;
        private boolean interleaved;

        private InterleavingProvider(NativeGitRepositoryProvider delegate, Runnable beforePublish) {
            this.delegate = delegate;
            this.beforePublish = beforePublish;
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
