package pro.deta.orion.component;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.decision.DecisionAnswer;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequest;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.crypto.OrionPasswordHashingService;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapConnectionDecisionTest {
    private static final PrincipalAddress ACTOR = PrincipalAddress.parse("system/operator");
    @TempDir Path directory;

    @Test
    void movedRepositoryStartsAndApprovalWritesTheNewUrlToTheLiveRepository() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            DecisionRequest request = fixture.decisions.list(ACTOR).getFirst();
            assertThat(request.description()).contains(fixture.previous.upstream().toString(),
                    fixture.upstream.toUri().toString());
            assertThat(fixture.desired.current().document().system().proxies()).containsExactly(fixture.previous);
            fixture.decisions.decide(request.id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("approve");
            assertThat(fixture.desired.current().document().system().proxies()).singleElement()
                    .satisfies(binding -> assertThat(binding.upstream()).isEqualTo(fixture.upstream.toUri()));
            assertThat(fixture.decisions.list(ACTOR)).isEmpty();
            assertThat(fixture.storage.load().isFailure()).isFalse();
            assertThat(fixture.provider.bootstrapChanges(fixture.desired.current().document())).isEmpty();
        }
    }

    @Test
    void rejectionKeepsTheYamlConnectionAndDoesNotRewriteXml() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            String revision = fixture.desired.current().revision().orElseThrow();
            fixture.decisions.decide(fixture.decisions.list(ACTOR).getFirst().id(), new DecisionAnswer(1, ACTOR))
                    .valueOrFailure("reject");
            assertThat(fixture.desired.current().revision()).contains(revision);
            assertThat(fixture.storage.load().isFailure()).isFalse();
            assertThat(fixture.provider.retry(fixture.previous.alias(),
                    () -> fixture.desired.current().document(), fixture.secrets).isFailure()).isFalse();
            assertThat(fixture.desired.current().document().system().proxies()).containsExactly(fixture.previous);
        }
    }

    @Test
    void approvalPreservesUnrelatedChangesMadeAfterTheRequest() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            DecisionRequest request = fixture.decisions.list(ACTOR).getFirst();
            GitProxyBinding unrelated = new GitProxyBinding(new RemoteAlias("other"),
                    directory.resolve("other.git").toUri(), "main", GitCredentialKind.NONE,
                    Optional.empty(), Optional.empty(), Set.of());
            fixture.acl.updatePrimaryConfiguration(fixture.desired.current().revision().orElseThrow(), document ->
                    new OrionDocument(new OrionDocument.SystemConfiguration(document.system().accessControl(),
                            document.system().https(), document.system().secrets(),
                            List.of(fixture.previous, unrelated)), document.organizations()),
                    new AccessControlSaveRequest("unrelated edit", UserEmail.EMPTY));
            fixture.decisions.decide(request.id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("approve");
            assertThat(fixture.desired.current().document().system().proxies()).contains(unrelated);
            assertThat(fixture.desired.current().document().system().proxies()).filteredOn(
                    binding -> binding.alias().equals(fixture.previous.alias())).singleElement()
                    .satisfies(binding -> assertThat(binding.upstream()).isEqualTo(fixture.upstream.toUri()));
        }
    }

    @Test
    void approvalDoesNotOverwriteConfigurationChangedAfterTheRequest() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.start();
            DecisionRequest request = fixture.decisions.list(ACTOR).getFirst();
            GitProxyBinding concurrent = new GitProxyBinding(fixture.previous.alias(), fixture.previous.upstream(),
                    "refs/heads/other", GitCredentialKind.NONE, Optional.empty(), Optional.empty(), Set.of());
            fixture.acl.updatePrimaryConfiguration(fixture.desired.current().revision().orElseThrow(),
                    document -> withBinding(concurrent), new AccessControlSaveRequest("concurrent edit", UserEmail.EMPTY));
            fixture.decisions.decide(request.id(), new DecisionAnswer(0, ACTOR)).valueOrFailure("approve");
            assertThat(fixture.desired.current().document().system().proxies()).containsExactly(concurrent);
            assertThat(fixture.storage.load().isFailure()).isFalse();
        }
    }

    private static OrionDocument withBinding(GitProxyBinding binding) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(),
                List.of(), List.of(binding)), List.of());
    }

    private static final class Fixture implements AutoCloseable {
        final Path upstream;
        final GitProxyBinding previous;
        final OrionDesiredState desired = new OrionDesiredState();
        final DecisionRegistry decisions = new DecisionRegistry(10, Runnable::run, (actor, scope) -> true);
        final OrionKeyMaterial material;
        final ProxyAwareNativeGitRepositoryProvider provider;
        final AccessControlStorage storage;
        final OrionAccessControlServiceImpl acl;
        final ConfigurationSecrets secrets;

        Fixture(Path directory) throws Exception {
            upstream = directory.resolve("moved.git");
            previous = new GitProxyBinding(new RemoteAlias("configuration"), directory.resolve("old.git").toUri(),
                    "main", GitCredentialKind.NONE, Optional.empty(), Optional.empty(), Set.of());
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
            provider = ProxyAwareNativeGitRepositoryProvider.bootstrap(
                    new InMemoryNativeGitRepositoryProvider(), Map.of());
            BootstrapSourceConfig source = new BootstrapSourceConfig();
            source.setLocation("git+" + upstream.toUri());
            source.setPath("orion.xml");
            ResolvedBootstrapSource resolved = provider.resolveProvisional("configuration", source, false);
            storage = new AccessControlStorageResolver(new BootstrapRepositorySources(List.of(resolved)), provider)
                    .resolve();
            KeyMaterialDescriptor signing = new KeyMaterialDescriptor(new KeyMaterialAlias("signing"),
                    KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1),
                    KeyMaterialScope.cluster("test"));
            try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("password".toCharArray())) {
                material = OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(), options,
                        new SigningMaterialSet(signing, List.of()), 2048, true);
            }
            acl = new OrionAccessControlServiceImpl(storage, new OrionPasswordHashingService(), null,
                    OrionRuntimeOptions.defaults(), material.serverIdentity(), desired);
            acl.reload("fixture");
            secrets = new ConfigurationSecrets(() -> desired.current().document(), material.configurationCipher());
        }

        void start() {
            OrionRuntimeModule.bootstrapProxies(storage, provider, material.configurationCipher(), secrets,
                    desired, acl, decisions).run();
            assertThat(decisions.list(ACTOR)).hasSize(1);
        }

        @Override
        public void close() {
            decisions.close();
            material.close();
        }
    }
}
