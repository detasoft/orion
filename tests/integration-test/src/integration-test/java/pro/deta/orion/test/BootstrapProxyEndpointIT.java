package pro.deta.orion.test;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.PASSWORD_ENV;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.configureSources;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.materialBytes;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.runtimeComponent;

/** Git clients address a persisted proxy alias while repository and branch grants protect its private cache. */
class BootstrapProxyEndpointIT {
    private static final String ENDPOINT = "proxy/system/configuration";
    private static final String REF = "refs/heads/main";
    private static final String PASSWORD = "proxy-client-password";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"http", "ssh"})
    void scopedClientsCloneAndPushThroughAliasAcrossRestartWhileCacheRemainsPrivate(String transport)
            throws Exception {
        var upstreamConfiguration = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("upstream"),
                config -> config.getTransport().getSsh().setEnabled(true));
        var target = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("target"),
                config -> config.getTransport().getSsh().setEnabled(true));
        target.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
        target.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var writerKey = generator.generateKeyPair();
        var readerKey = generator.generateKeyPair();
        var outsiderKey = generator.generateKeyPair();
        var rootKey = generator.generateKeyPair();
        try (var upstream = RuntimeHttpTestSupport.start(upstreamConfiguration)) {
            var environment = configureSources(tempDir, target, upstream, transport);
            var repository = upstream.repositoryProvider().create("bootstrap-inputs")
                    .valueOrFailure("bootstrap upstream");
            var document = OrionXml.read(new ByteArrayInputStream(
                    upstream.accessControlService().accessControlConfigurationFile()));
            var aclDraft = document.system().accessControl().toDraft();
            var rootDraft = aclDraft.getUsers().getFirst();
            for (String name : List.of("proxy/system/*", "bootstrap/*")) {
                rootDraft.addGrant("probe-" + rootDraft.getGrants().size())
                        .addKey(AccessControl.GrantKey.REPOSITORY, name)
                        .addKey(AccessControl.GrantKey.READ, "true")
                        .addKey(AccessControl.GrantKey.BRANCH, "*");
            }
            var xml = new ByteArrayOutputStream();
            OrionXml.write(document.replaceAccessControl(aclDraft.toAccessControl()), xml);
            repository.saveFiles(REF, Map.of(
                    "orion.xml", xml.toByteArray(),
                    "material.p12", materialBytes(target, environment)), "seed inputs", GitCommitAuthor.EMPTY);
            for (int launch = 0; launch < 2; launch++) {
                try (var bootstrap = BootstrapContext.open(target, environment)) {
                    var component = runtimeComponent(target, bootstrap);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                        lifecycle.waitForStarting();
                        if (launch == 0) {
                            var acl = component.orionAccessControlService();
                            for (var user : List.of(
                                    user("writer", writerKey, ENDPOINT, true),
                                    user("reader", readerKey, ENDPOINT, false),
                                    user("outsider", outsiderKey, "ordinary", true))) {
                                acl.createOrUpdateUser(user);
                            }
                            acl.addKeyToUser("root", PublicKeyEntry.toString(rootKey.getPublic()));
                            bootstrap.repositoryProvider().create("ordinary").valueOrFailure("ordinary repository")
                                    .saveFiles(REF, Map.of("file", new byte[]{1}), "ordinary seed", GitCommitAuthor.EMPTY);
                        }
                        String cache = bootstrap.repositorySources().required(BootstrapRepositorySources.CONFIGURATION)
                                .repositoryName().orElseThrow();
                        char[] rootPassword = upstream.accessControlService()
                                .plainRootToken(PlainRootTokenAccessForTests.create());
                        try (var writer = client(target, bootstrap, transport, "writer", writerKey, PASSWORD.toCharArray());
                             var reader = client(target, bootstrap, transport, "reader", readerKey, PASSWORD.toCharArray());
                             var outsider = client(target, bootstrap, transport, "outsider", outsiderKey,
                                     PASSWORD.toCharArray());
                             var root = client(target, bootstrap, transport, "root", rootKey, rootPassword)) {
                            assertThat(Git.lsRemoteRepository().setRemote(root.uri(ENDPOINT))
                                    .setTransportConfigCallback(root.callback()).call()).isNotEmpty();
                            assertThat(Git.lsRemoteRepository().setRemote(outsider.uri("ordinary"))
                                    .setTransportConfigCallback(outsider.callback()).call()).isNotEmpty();
                            assertDenied(outsider, ENDPOINT);
                            assertDenied(writer, cache);
                            assertDenied(root, cache);
                            assertDenied(root, "bootstrap%2f" + cache.substring("bootstrap/".length()));
                            assertDenied(root, "proxy/system/missing");
                            try (var clone = Git.cloneRepository().setURI(writer.uri(ENDPOINT))
                                    .setDirectory(tempDir.resolve("clone-" + launch).toFile())
                                    .setBranch(REF).setTransportConfigCallback(writer.callback()).call()) {
                                assertThat(Files.readString(clone.getRepository().getWorkTree().toPath()
                                        .resolve("orion.xml"))).contains("<orion");
                                Path marker = clone.getRepository().getWorkTree().toPath().resolve("client-marker");
                                Files.writeString(marker, "launch " + launch);
                                clone.add().addFilepattern("client-marker").call();
                                var commit = clone.commit().setMessage("push through public proxy")
                                        .setAuthor("proxy client", "client@example.test").call();
                                var beforeDenied = repository.refs();
                                assertThatThrownBy(() -> clone.push().setRemote(reader.uri(ENDPOINT))
                                        .setRefSpecs(new RefSpec(REF + ":" + REF))
                                        .setTransportConfigCallback(reader.callback()).call())
                                        .isInstanceOf(TransportException.class);
                                assertThat(repository.refs()).isEqualTo(beforeDenied);
                                var branchDenied = clone.push().setRemote(writer.uri(ENDPOINT))
                                        .setRefSpecs(new RefSpec(REF + ":refs/heads/forbidden"))
                                        .setTransportConfigCallback(writer.callback()).call();
                                for (var push : branchDenied) {
                                    assertThat(push.getRemoteUpdates()).singleElement()
                                            .extracting(RemoteRefUpdate::getStatus)
                                            .isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
                                }
                                assertThat(repository.refs()).isEqualTo(beforeDenied);
                                var accepted = clone.push().setRemote(writer.uri(ENDPOINT))
                                        .setRefSpecs(new RefSpec(REF + ":" + REF))
                                        .setTransportConfigCallback(writer.callback()).call();
                                for (var push : accepted) {
                                    assertThat(push.getRemoteUpdates()).singleElement()
                                            .extracting(RemoteRefUpdate::getStatus).isEqualTo(RemoteRefUpdate.Status.OK);
                                }
                                assertThat(repository.refs()).containsEntry(REF, commit.name());
                                assertThat(repository.loadFiles(REF, List.of("client-marker")).files())
                                        .containsEntry("client-marker", ("launch " + launch).getBytes(StandardCharsets.UTF_8));
                                assertThat(Git.lsRemoteRepository().setRemote(reader.uri(ENDPOINT))
                                        .setTransportConfigCallback(reader.callback()).call())
                                        .anySatisfy(ref -> {
                                            assertThat(ref.getName()).isEqualTo(REF);
                                            assertThat(ref.getObjectId().name()).isEqualTo(commit.name());
                                        });
                            }
                        } finally {
                            java.util.Arrays.fill(rootPassword, '\0');
                        }
                    } finally {
                        lifecycle.shutdownApplication();
                        lifecycle.waitForShutdown();
                    }
                }
            }
        }
    }

    private static AccessControlUserUpdate user(String name, KeyPair key, String repository, boolean write) {
        String hash = new OrionPasswordHashingService().calculateHash(
                PasswordHashingAlgorithm.SHA1, PASSWORD.toCharArray());
        return new AccessControlUserUpdate(name, name + "@example.test", List.of(
                new AccessControlCredentialUpdate(AccessControl.CredentialType.SHA1, hash),
                new AccessControlCredentialUpdate(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                        PublicKeyEntry.toString(key.getPublic()))),
                List.of(new AccessControlRepositoryGrantUpdate(repository, true, write, false, false, "main")));
    }

    private Client client(OrionConfiguration configuration, BootstrapContext bootstrap, String transport,
            String username, KeyPair key, char[] password) throws Exception {
        if ("http".equals(transport)) {
            var http = configuration.getTransport().getHttp();
            var base = new URL("http", http.getAddress(), http.getPort(), "/r/");
            String token = TestBearerTokens.issueToken(new URL(base, "/api/admin/token"), username, password, 600);
            return new Client(base.toString(), selected -> ((TransportHttp) selected)
                    .setAdditionalHeaders(Map.of("Authorization", TestBearerTokens.bearer(token))), null);
        }
        Path home = Files.createDirectories(tempDir.resolve("ssh-" + username));
        Path sshDirectory = Files.createDirectories(home.resolve(".ssh"));
        List<PublicKey> hostKeys = new ArrayList<>();
        for (var hostKey : bootstrap.sshHostKeys().keyPairs()) {
            hostKeys.add(hostKey.getPublic());
        }
        var factory = new SshdSessionFactoryBuilder().setHomeDirectory(home.toFile())
                .setSshDirectory(sshDirectory.toFile()).setPreferredAuthentications("publickey")
                .setDefaultKeysProvider(directory -> List.of(key))
                .setServerKeyDatabase((homeDirectory, directory) -> new ServerKeyDatabase() {
                    @Override
                    public List<PublicKey> lookup(String address, InetSocketAddress remote, Configuration config) {
                        return hostKeys;
                    }

                    @Override
                    public boolean accept(String address, InetSocketAddress remote, PublicKey serverKey,
                            Configuration config, org.eclipse.jgit.transport.CredentialsProvider credentials) {
                        for (PublicKey expected : hostKeys) {
                            if (org.apache.sshd.common.config.keys.KeyUtils.compareKeys(expected, serverKey)) {
                                return true;
                            }
                        }
                        return false;
                    }
                }).build(null);
        return new Client("ssh://" + username + "@localhost:" + configuration.getTransport().getSsh().getPort() + "/",
                selected -> ((SshTransport) selected).setSshSessionFactory(factory), factory);
    }

    private static void assertDenied(Client client, String repository) {
        assertThatThrownBy(() -> Git.lsRemoteRepository().setRemote(client.uri(repository))
                .setTransportConfigCallback(client.callback()).call()).isInstanceOf(TransportException.class);
    }

    private record Client(String base, TransportConfigCallback callback, SshdSessionFactory factory)
            implements AutoCloseable {
        String uri(String repository) {
            return base + repository + ".git";
        }

        @Override
        public void close() {
            if (factory != null) {
                factory.close();
            }
        }
    }
}
