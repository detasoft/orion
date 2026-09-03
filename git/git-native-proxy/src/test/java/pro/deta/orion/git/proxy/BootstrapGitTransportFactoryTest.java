package pro.deta.orion.git.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientResult;
import pro.deta.orion.git.client.GitSshClientTransport;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BootstrapGitTransportFactoryTest {
    @Test
    void sendsBearerCredentialOnSmartHttpDiscovery() throws Exception {
        assertHttpAuthorization(
                "http-bearer",
                Map.of(),
                "token-value",
                "Bearer token-value");
    }

    @Test
    void sendsBasicCredentialOnSmartHttpDiscovery() throws Exception {
        assertHttpAuthorization(
                "http-basic",
                Map.of("credentialUsername", "orion"),
                "password-value",
                "Basic b3Jpb246cGFzc3dvcmQtdmFsdWU=");
    }

    @Test
    void createsPasswordAndPrivateKeySshTransports(@TempDir Path temporaryDirectory)
            throws Exception {
        Path secureDirectory = temporaryDirectory.toRealPath();
        Path knownHosts = secureDirectory.resolve("known_hosts");
        Files.writeString(knownHosts, "");
        if (Files.getFileStore(secureDirectory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                    secureDirectory,
                    PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(
                    knownHosts,
                    PosixFilePermissions.fromString("rw-r--r--"));
        }
        Map<String, String> environment = Map.of(
                "SSH_PASSWORD", "password-value",
                "SSH_PRIVATE_KEY", privateKey());
        BootstrapGitTransportFactory factory = new BootstrapGitTransportFactory(
                new BootstrapSecretResolver(environment));

        Class<?> passwordTransport = factory.withTransport(
                sshLocation("ssh-password", "env:SSH_PASSWORD", knownHosts),
                transport -> transport.getClass());
        Class<?> privateKeyTransport = factory.withTransport(
                sshLocation("ssh-private-key", "env:SSH_PRIVATE_KEY", knownHosts),
                transport -> transport.getClass());

        assertThat(passwordTransport).isEqualTo(GitSshClientTransport.class);
        assertThat(privateKeyTransport).isEqualTo(GitSshClientTransport.class);
    }

    @Test
    void rejectsGroupWritableKnownHosts(@TempDir Path temporaryDirectory) throws Exception {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"));
        Path knownHosts = temporaryDirectory.toRealPath().resolve("known_hosts");
        Files.writeString(knownHosts, "example.test ssh-rsa key");
        Files.setPosixFilePermissions(knownHosts, PosixFilePermissions.fromString("rw-rw-r--"));
        BootstrapGitTransportFactory factory = new BootstrapGitTransportFactory(
                new BootstrapSecretResolver(Map.of("SSH_PASSWORD", "password-value")));

        assertThatThrownBy(() -> factory.withTransport(
                sshLocation("ssh-password", "env:SSH_PASSWORD", knownHosts),
                transport -> transport.getClass()))
                .isInstanceOf(BootstrapGitProxyException.class)
                .hasMessage("Remote Git bootstrap failed during SSH host-key configuration")
                .hasMessageNotContaining(knownHosts.toString());
    }

    @Test
    void rejectsKnownHostsInGroupWritableDirectory(@TempDir Path temporaryDirectory) throws Exception {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"));
        Path knownHostsDirectory = temporaryDirectory.toRealPath().resolve("known-hosts");
        Files.createDirectory(knownHostsDirectory);
        Path knownHosts = knownHostsDirectory.resolve("known_hosts");
        Files.writeString(knownHosts, "example.test ssh-rsa key");
        Files.setPosixFilePermissions(knownHosts, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(
                knownHostsDirectory,
                PosixFilePermissions.fromString("rwxrwxr-x"));
        BootstrapGitTransportFactory factory = new BootstrapGitTransportFactory(
                new BootstrapSecretResolver(Map.of("SSH_PASSWORD", "password-value")));

        assertThatThrownBy(() -> factory.withTransport(
                sshLocation("ssh-password", "env:SSH_PASSWORD", knownHosts),
                transport -> transport.getClass()))
                .isInstanceOf(BootstrapGitProxyException.class)
                .hasMessage("Remote Git bootstrap failed during SSH host-key configuration")
                .hasMessageNotContaining(knownHosts.toString());
    }

    private static void assertHttpAuthorization(
            String credentialKind,
            Map<String, String> extraAuth,
            String credential,
            String expected) throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0);
        server.createContext("/repository.git", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/repository.git");
            BootstrapGitLocation location = httpLocation(uri, credentialKind, extraAuth);
            BootstrapGitTransportFactory factory = new BootstrapGitTransportFactory(
                    new BootstrapSecretResolver(Map.of("GIT_CREDENTIAL", credential)));

            GitClientResult<GitRemoteAdvertisement> result = factory.withTransport(
                    location,
                    transport -> new GitUploadPackClient(transport).discover(
                            location.remoteUri(),
                            GitClientOptions.defaults()));

            assertThat(result).isInstanceOf(GitClientResult.Failed.class);
            assertThat(authorization).hasValue(expected);
        } finally {
            server.stop(0);
        }
    }

    private static BootstrapGitLocation httpLocation(
            URI remote,
            String credentialKind,
            Map<String, String> extraAuth) {
        Map<String, String> auth = new LinkedHashMap<>();
        auth.put("credentialKind", credentialKind);
        auth.put("credential", "env:GIT_CREDENTIAL");
        auth.putAll(extraAuth);
        return location("git+" + remote + "?ref=main", auth);
    }

    private static BootstrapGitLocation sshLocation(
            String credentialKind,
            String credential,
            Path knownHosts) {
        return location(
                "git+ssh://git@example.test/repository.git?ref=main",
                Map.of(
                        "credentialKind", credentialKind,
                        "credential", credential,
                        "knownHosts", knownHosts.toUri().toString()));
    }

    private static BootstrapGitLocation location(
            String remote,
            Map<String, String> auth) {
        BootstrapSourceConfig config = new BootstrapSourceConfig();
        config.setLocation(remote);
        config.setPath("orion.xml");
        config.setAuth(auth);
        return BootstrapGitLocation.parse(config);
    }

    private static String privateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----\n";
    }
}
