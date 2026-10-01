package pro.deta.orion.test;

import pro.deta.orion.internal.UserEmail;

import pro.deta.orion.auth.SshCredentialUpdateResult;

import pro.deta.orion.config.OrionConfigurationEdit;

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
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.bootstrap.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URI;
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
                    upstream.accessControlService().accessControlConfigurationFile().content()));
            AccessControl currentAcl = document.system().accessControl();
            User currentRoot = currentAcl.users().getFirst();
            List<Grant> grants = new ArrayList<>(currentRoot.grants());
            for (String name : List.of("proxy/system/*", "bootstrap")) {
                grants.add(new Grant("probe-" + grants.size(), List.of(
                        new GrantExpression(AccessControl.GrantKey.REPOSITORY, name),
                        new GrantExpression(AccessControl.GrantKey.READ, "true"),
                        new GrantExpression(AccessControl.GrantKey.BRANCH, "*"))));
            }
            User updatedRoot = new User(currentRoot.id(), currentRoot.first(),
                    currentRoot.last(), currentRoot.email(), List.of(new Credential(
                            AccessControl.CredentialType.SHA1,
                            new OrionPasswordHashingService().calculateHash(
                                    PasswordHashingAlgorithm.SHA1, PASSWORD.toCharArray()))),
                    currentRoot.roles(), grants);
            List<User> users = new ArrayList<>(currentAcl.users());
            users.set(0, updatedRoot);
            var xml = new ByteArrayOutputStream();
            OrionXml.write(document.replaceAccessControl(
                    new AccessControl(users, currentAcl.roles(), currentAcl.grants())), xml);
            repository.files().withAccess(REF, "seed inputs", GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write("orion.xml", xml.toByteArray());
                fileAccess.write("material.p12", materialBytes(target, environment));
                fileAccess.apply();
                return null;
            });
            for (int launch = 0; launch < 2; launch++) {
                try (var bootstrap = BootstrapContext.open(target, environment)) {
                    var component = runtimeComponent(target, bootstrap);
                    var lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                        lifecycle.waitForStarting();
                        int port = "http".equals(transport)
                                ? component.httpTransport().boundHttpPort() : component.sshTransport().boundPort();
                        if (launch == 0) {
                            var acl = component.orionAccessControlService();
                            try (OrionConfigurationEdit edit = component.configurationEditor().edit()) {
                                assertThat(acl.addSshCredentials(edit, "root",
                                        List.of(PublicKeyEntry.toString(rootKey.getPublic()))))
                                        .isInstanceOf(SshCredentialUpdateResult.Success.class);
                                edit.apply("add root test key", UserEmail.EMPTY);
                            }
                            URL proxyApi = URI.create("http://127.0.0.1:"
                                    + component.httpTransport().boundHttpPort() + "/api/admin/proxies").toURL();
                            String token = pro.deta.orion.test.integration.OrionTestRootAccess.issueToken(
                                    acl, rootKey.getPublic(), 600);
                            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            var before = mapper.readTree(RuntimeHttpTestSupport.request(
                                    "GET", proxyApi, TestBearerTokens.bearer(token)).body());
                            var source = target.getBootstrap().getAccessControl();
                            Map<String, Object> command = new java.util.LinkedHashMap<>();
                            command.put("action", "create");
                            command.put("scope", "system");
                            command.put("revision", before.get("revision").asText());
                            command.put("alias", "configuration");
                            command.put("upstream", source.getLocation().substring(4));
                            command.put("ref", REF);
                            command.put("credentialKind", transport.equals("http") ? "TOKEN" : "PRIVATE_KEY");
                            command.put("credential", Files.readString(Path.of(URI.create(
                                    source.getAuth().get("credential")))));
                            if (transport.equals("ssh")) {
                                command.put("knownHosts", source.getAuth().get("knownHosts").lines().toList());
                            }
                            assertThat(RuntimeHttpTestSupport.request("POST", proxyApi,
                                    TestBearerTokens.bearer(token), "application/json",
                                    mapper.writeValueAsBytes(command)).status()).isEqualTo(409);
                            before = mapper.readTree(RuntimeHttpTestSupport.request(
                                    "GET", proxyApi, TestBearerTokens.bearer(token)).body());
                            command.put("revision", before.get("revision").asText());
                            assertThat(RuntimeHttpTestSupport.request("POST", proxyApi,
                                    TestBearerTokens.bearer(token), "application/json",
                                    mapper.writeValueAsBytes(command)).status()).isEqualTo(201);
                            for (var user : List.of(
                                    user("writer", writerKey, ENDPOINT, true),
                                    user("reader", readerKey, ENDPOINT, false),
                                    user("outsider", outsiderKey, "ordinary", true))) {
                                try (OrionConfigurationEdit edit = component.configurationEditor().edit()) {
                                    acl.createOrUpdateUser(edit, user);
                                    edit.apply("createOrUpdateUser() " + user.id(),
                                            new UserEmail(user.id(), user.email()));
                                }
                            }
                            bootstrap.repositoryProvider().create("ordinary").valueOrFailure("ordinary repository")
                                    .files().withAccess(REF, "ordinary seed", GitCommitAuthor.EMPTY,
                                            fileAccess -> {
                                fileAccess.write("file", new byte[]{1});
                                fileAccess.apply();
                                return null;
                            });
                        }
                        String cache = bootstrap.repositoryFactory()
                                .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE)
                                        .orElseThrow();
                        char[] rootPassword = PASSWORD.toCharArray();
                        try (var writer = client(target, bootstrap, transport, port,
                                     "writer", writerKey, PASSWORD.toCharArray());
                             var reader = client(target, bootstrap, transport, port,
                                     "reader", readerKey, PASSWORD.toCharArray());
                             var outsider = client(target, bootstrap, transport, port, "outsider", outsiderKey,
                                     PASSWORD.toCharArray());
                             var root = client(target, bootstrap, transport, port, "root", rootKey, rootPassword)) {
                            assertThat(Git.lsRemoteRepository().setRemote(root.uri(ENDPOINT))
                                    .setTransportConfigCallback(root.callback()).call()).isNotEmpty();
                            assertThat(Git.lsRemoteRepository().setRemote(outsider.uri("ordinary"))
                                    .setTransportConfigCallback(outsider.callback()).call()).isNotEmpty();
                            assertDenied(outsider, ENDPOINT);
                            assertDenied(writer, cache);
                            assertDenied(root, cache);
                            assertDenied(root, "%62ootstrap");
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
                                assertThat(repository.files().readBytes(REF, "client-marker"))
                                        .isEqualTo(("launch " + launch).getBytes(StandardCharsets.UTF_8));
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

    private Client client(OrionConfiguration configuration, BootstrapContext bootstrap, String transport, int port,
            String username, KeyPair key, char[] password) throws Exception {
        if ("http".equals(transport)) {
            var http = configuration.getTransport().getHttp();
            var base = new URL("http", http.getAddress(), port, "/r/");
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
        return new Client("ssh://" + username + "@localhost:" + port + "/",
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
