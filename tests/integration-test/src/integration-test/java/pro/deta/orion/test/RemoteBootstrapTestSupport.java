package pro.deta.orion.test;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.transport.git.SshHostKeyLifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Native upstream authentication, material and runtime wiring shared by remote bootstrap scenarios. */
final class RemoteBootstrapTestSupport {
    static final String PASSWORD_ENV = "BOOTSTRAP_TEST_PASSWORD";

    private RemoteBootstrapTestSupport() {
    }

    static OrionComponent runtimeComponent(
            OrionConfiguration configuration, BootstrapContext context) {
        return DaggerOrionComponent.builder()
                .configurationProvider(() -> configuration)
                .runtimeOptions(OrionRuntimeOptions.defaults())
                .serverIdentityCapability(context.serverIdentity())
                .acmeKeyMaterialCapability(context.acmeKeyMaterial())
                .tlsCapability(context.tlsKeyMaterial())
                .sshHostKeyCapability(context.sshHostKeys())
                .configurationCipherCapability(context.configurationCipher())
                .nativeGitRepositoryProvider(context.repositoryProvider())
                .bootstrapRepositorySources(context.repositorySources())
                .build();
    }

    static Map<String, String> configureSources(
            Path directory,
            OrionConfiguration configuration,
            RuntimeHttpTestSupport.StartedOrion upstream,
            String transport) throws Exception {
        String location;
        String credential;
        Path credentialFile = directory.toRealPath().resolve("upstream-credential");
        String credentialReference = credentialFile.toUri().toString();
        Map<String, String> authentication;
        if ("http".equals(transport)) {
            credential = TestBearerTokens.issueRootToken(
                    upstream.accessControlService(), upstream.httpUrl("/api/admin/token"), 600);
            location = "git+" + upstream.httpUrl("/r/bootstrap-inputs.git");
            authentication = Map.of("credentialKind", "http-bearer", "credential", credentialReference);
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var key = generator.generateKeyPair();
            upstream.accessControlService().addKeyToUser("root", PublicKeyEntry.toString(key.getPublic()));
            credential = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            int port = upstream.configuration().getTransport().getSsh().getPort();
            Path knownHosts = directory.toRealPath().resolve("known_hosts");
            StringBuilder hosts = new StringBuilder();
            for (var hostKey : upstream.identity().sshHostKeys().keyPairs()) {
                hosts.append("[localhost]:").append(port).append(' ')
                        .append(PublicKeyEntry.toString(hostKey.getPublic())).append('\n');
            }
            Files.writeString(knownHosts, hosts);
            location = "git+ssh://root@localhost:" + port + "/bootstrap-inputs.git";
            authentication = Map.of("credentialKind", "ssh-private-key", "credential", credentialReference,
                    "knownHosts", knownHosts.toUri().toString());
        }
        Files.writeString(credentialFile, credential);
        if (Files.getFileStore(credentialFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(credentialFile, PosixFilePermissions.fromString("rw-------"));
        }
        for (BootstrapSourceConfig source : List.of(configuration.getBootstrap().getAccessControl(),
                configuration.getBootstrap().getKeyMaterial())) {
            source.setLocation(location);
            source.setAuth(authentication);
        }
        return Map.of(PASSWORD_ENV, "bootstrap-test-password");
    }

    static byte[] materialBytes(OrionConfiguration configuration, Map<String, String> environment)
            throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (var material = OrionKeyMaterialFactory.open(configuration, environment, store, true)) {
            SshHostKeyLifecycle.open(material.sshHostKeyMaterial(), List.of());
        }
        return store.read().orElseThrow().bytes();
    }

}
