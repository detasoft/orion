package pro.deta.orion.component;

import java.util.Set;

import pro.deta.orion.config.OrionConfigurationEditor;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.config.NativeGitOrionConfigurationStorage;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.decision.DecisionAnswer;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequest;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapSourceConfig;
import pro.deta.orion.acl.RootPasswordReset;
import pro.deta.orion.schema.orion.v2.GitCredentialKind;
import pro.deta.orion.schema.orion.v2.GitProxyBinding;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.v2.RemoteAlias;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import pro.deta.orion.internal.ChangeMetadata;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class BootstrapConnectionDecisionTest {
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    private static final PrincipalAddress ACTOR = PrincipalAddress.parse("system/operator");
    @TempDir Path directory;

    @Test
    void movedRepositoryStartsAndApprovalWritesTheNewUrlToTheLiveRepository() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            DecisionRequest request = fixture.decisions.list(ACTOR).getFirst();
            assertThat(request.description()).contains(fixture.previous.upstream(fixture.editor.current()
                    .result().valueOrFailure("Cannot read configuration document").system()).toString(),
                    fixture.upstream.toUri().toString());
            assertThat(fixture.editor.current().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().proxies())
                    .containsExactly(fixture.previous);
            fixture.decisions.decide(request.id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("approve");
            EVENTS.await();
            assertThat(fixture.editor.current().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().proxies()).singleElement()
                    .satisfies(binding -> assertThat(binding.upstream(fixture.editor.current().result()
                            .valueOrFailure("Cannot read configuration document")
                            .system())).isEqualTo(fixture.upstream.toUri()));
            assertThat(fixture.decisions.list(ACTOR)).isEmpty();
            assertThat(fixture.storage.load().result().isFailure()).isFalse();
            assertThat(fixture.provider.bootstrapChanges(fixture.editor.current().result()
                    .valueOrFailure("Cannot read configuration document"))).isEmpty();
        }
    }

    @Test
    void rejectionKeepsTheYamlConnectionAndDoesNotRewriteXml() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            String revision = ((OrionRevision.Stored) fixture.editor.current().revision()).value();
            fixture.decisions.decide(fixture.decisions.list(ACTOR).getFirst().id(), new DecisionAnswer(1, ACTOR))
                    .valueOrFailure("reject");
            assertThat(fixture.editor.current().revision()).isEqualTo(new OrionRevision.Stored(revision));
            assertThat(fixture.storage.load().result().isFailure()).isFalse();
            assertThat(fixture.provider.retry(fixture.previous.alias(),
                    () -> fixture.editor.current().result()
                            .valueOrFailure("Cannot read configuration document"), fixture.secrets).isFailure()).isFalse();
            assertThat(fixture.editor.current().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().proxies())
                    .containsExactly(fixture.previous);
        }
    }

    @Test
    void approvalPreservesUnrelatedChangesMadeAfterTheRequest() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            DecisionRequest request = fixture.decisions.list(ACTOR).getFirst();
            GitProxyBinding unrelated = new GitProxyBinding(new RemoteAlias("other"),
                new GitProxyBinding.Direct(directory.resolve("other.git").toUri(), GitCredentialKind.NONE,
                        Optional.empty(), Optional.empty()), "main");
            fixture.editor
                    .edit(
                            fixture.editor.current().revision(),
                            new ChangeMetadata(UserEmail.EMPTY, "unrelated edit"))
                    .update(
                            document ->
                                    new OrionDocument(
                                            new OrionDocument.SystemConfiguration(
                                                    document.system().accessControl(),
                                                    document.system().https(),
                                                    document.system().secrets(),
                                                    List.of(fixture.previous, unrelated),
                                                    document.system().connections(),
                                                    document.system().oidcProviders(), document.system().invitations()),
                                            document.organizations()))
                    .apply();
            EVENTS.await();
            fixture.decisions.decide(request.id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("approve");
            EVENTS.await();
            assertThat(fixture.editor.current().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().proxies()).contains(unrelated);
            assertThat(fixture.editor.current().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().proxies()).filteredOn(
                    binding -> binding.alias().equals(fixture.previous.alias())).singleElement()
                    .satisfies(binding -> assertThat(binding.upstream(fixture.editor.current().result()
                            .valueOrFailure("Cannot read configuration document")
                            .system())).isEqualTo(fixture.upstream.toUri()));
        }
    }

    @Test
    void approvalDoesNotOverwriteConfigurationChangedAfterTheRequest() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            DecisionRequest request = fixture.decisions.list(ACTOR).getFirst();
            GitProxyBinding concurrent = new GitProxyBinding(fixture.previous.alias(),
                new GitProxyBinding.Direct(fixture.previous.upstream(fixture.editor.current().result()
                        .valueOrFailure("Cannot read configuration document")
                        .system()), GitCredentialKind.NONE, Optional.empty(), Optional.empty()), "refs/heads/other");
            fixture.editor
                    .edit(
                            fixture.editor.current().revision(),
                            new ChangeMetadata(UserEmail.EMPTY, "concurrent edit"))
                    .update(document -> withBinding(concurrent))
                    .apply();
            EVENTS.await();
            fixture.decisions.decide(request.id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("approve");
            EVENTS.await();
            assertThat(fixture.editor.current().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().proxies()).containsExactly(concurrent);
            assertThat(fixture.storage.load().result().isFailure()).isFalse();
        }
    }

    private static OrionDocument withBinding(GitProxyBinding binding) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(),
                List.of(), List.of(binding), List.of(),
                List.of(), List.of()), List.of());
    }

    private static final class Fixture implements AutoCloseable {
        final Path upstream;
        final GitProxyBinding previous;
        final DecisionRegistry decisions = new DecisionRegistry(10, Runnable::run, (actor, scope) -> true);
        final OrionKeyMaterial material;
        final NativeGitRepositoryFactory provider;
        final OrionVersionedConfigurationStorage storage;
        final OrionConfigurationEditor editor;
        final OrionAccessControlServiceImpl acl;
        final ConfigurationSecrets secrets;
        final pro.deta.orion.git.s3.S3Transport transport = new pro.deta.orion.git.s3.S3Transport();
        final pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory configured =
                new pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory(
                        pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(
                                EVENTS.manager()),
                        transport,
                        EVENTS.manager());

        Fixture(Path directory) throws Exception {
            upstream = directory.resolve("moved.git");
            previous = new GitProxyBinding(new RemoteAlias("material"),
                new GitProxyBinding.Direct(directory.resolve("old.git").toUri(), GitCredentialKind.NONE,
                        Optional.empty(), Optional.empty()), "main");
            Path worktree = directory.resolve("worktree");
            try (Git git = Git.init().setDirectory(worktree.toFile()).setInitialBranch("main").call()) {
                ByteArrayOutputStream xml = new ByteArrayOutputStream();
                OrionXml.write(withBinding(previous), xml);
                Files.write(worktree.resolve("orion.xml"), xml.toByteArray());
                git.add().addFilepattern(".").call();
                git.commit().setMessage("configuration before relocation")
                        .setAuthor("Test", "test@example.invalid").call();
                try (Git ignored = Git.cloneRepository().setURI(worktree.toUri().toString())
                        .setDirectory(upstream.toFile()).setBare(true).call()) {
                    // The moved repository contains its former address in orion.xml.
                }
            }
            provider = NativeGitRepositoryFactory.bootstrap(
                    configured, Map.of());
            BootstrapSourceConfig source = new BootstrapSourceConfig();
            source.setLocation("git+" + upstream.toUri());
            source.setPath("orion.xml");
            provider.resolveProvisional(NativeGitRepositoryFactory.MATERIAL_SOURCE, source, false);
            pro.deta.orion.bootstrap.config.BootstrapConfigurationSourceConfig configuration =
                    new pro.deta.orion.bootstrap.config.BootstrapConfigurationSourceConfig();
            configuration.setLocation(source.getLocation());
            configuration.setPath(source.getPath());
            provider.resolveProvisional(NativeGitRepositoryFactory.CONFIGURATION_SOURCE, configuration, false);
            storage = new NativeGitOrionConfigurationStorage(provider, configuration);
            KeyMaterialDescriptor signing = new KeyMaterialDescriptor("signing",
                    Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
            try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("password".toCharArray())) {
                material = OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(), options);
                material.withModification(modification -> {
                    modification.generateKey(signing, 2048);
                    modification.apply();
                });
            }
            editor = new OrionConfigurationEditor(storage,
                material,
                EVENTS.manager());
            acl =
                    new OrionAccessControlServiceImpl(
                            new OrionPasswordHashingService(),
                            new RootPasswordReset(false),
                            material, new SigningMaterialSet(signing, List.of()),
                            new pro.deta.orion.bootstrap.config.BootstrapConfiguration(),
                            editor);
            editor.onStart();
            acl.onStart();
            secrets =
                    new ConfigurationSecrets(
                            () -> editor.current().result()
                                    .valueOrFailure("Cannot read configuration document"), material);
        }

        void start() {
            OrionRuntimeModule.bootstrapProxies(
                            configured,
                            provider,
                            material, secrets,
                    editor, decisions,
                    OrionRuntimeModule.connectionFailures(decisions),
                    OrionRuntimeModule.proxyHostKeyDecisions(editor, record -> { })).run();
            assertThat(decisions.list(ACTOR)).hasSize(1);
        }

        @Override
        public void close() {
            acl.onStop();
            editor.onStop();
            decisions.close();
            transport.close();
            material.close();
        }
    }
}
