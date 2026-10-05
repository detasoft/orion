package pro.deta.orion.test;

import pro.deta.orion.internal.UserEmail;

import pro.deta.orion.config.OrionConfigurationEdit;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.bootstrap.config.BootstrapSourceConfig;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.bootstrap.config.OrionRuntimeOptions;
import pro.deta.orion.transport.git.SshHostKeyLifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Native upstream authentication, material and runtime wiring shared by remote bootstrap scenarios. */
final class RemoteBootstrapTestSupport {
    static final String PASSWORD_ENV = "BOOTSTRAP_TEST_PASSWORD";

    private RemoteBootstrapTestSupport() {
    }

    static OrionComponent runtimeComponent(
            BootstrapConfiguration configuration, BootstrapContext context) {
        return DaggerOrionComponent.builder()
                .bootstrapConfiguration(configuration)
                .runtimeOptions(OrionRuntimeOptions.defaults())
                .serverIdentityCapability(context.serverIdentity())
                .acmeKeyMaterialCapability(context.acmeKeyMaterial())
                .configurationMaterialCapability(context.configurationMaterial())
                .keyMaterialAdministrationCapability(context.keyMaterialAdministration())
                .initialConfiguration(context.initialConfiguration())
                .tlsCapability(context.tlsKeyMaterial())
                .sshHostKeyCapability(context.sshHostKeys())
                .configurationCipherCapability(context.configurationCipher())
                .nativeGitRepositoryProvider(context.repositoryFactory())
                .configuredRepositoryFactory(context.storageFactory())
                .s3Transport(context.s3Transport())
                .build();
    }

    static Map<String, String> configureSources(
            Path directory,
            BootstrapConfiguration configuration,
            RuntimeHttpTestSupport.StartedOrion upstream,
            String transport) throws Exception {
        String location;
        String credential;
        Path credentialFile = directory.toRealPath().resolve("upstream-credential");
        String credentialReference = credentialFile.toUri().toString();
        Map<String, String> authentication;
        if ("http".equals(transport)) {
            credential = TestBearerTokens.issueRootToken(upstream.accessControlService(),
                    upstream.component().configurationEditor(), 600);
            location = "git+" + upstream.httpUrl("/r/bootstrap-inputs.git");
            authentication = Map.of("credentialKind", "token", "credential", credentialReference);
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var key = generator.generateKeyPair();
            SshCredentialListResult listed = upstream.accessControlService().listSshCredentials("root");
            assertThat(listed).isInstanceOf(SshCredentialListResult.Success.class);
            if (((SshCredentialListResult.Success) listed).credentials().isEmpty()) {
                TestBearerTokens.enrollRootKey(upstream.accessControlService(), upstream.component().configurationEditor(), key);
            } else {
                try (OrionConfigurationEdit edit =
                        upstream.component().configurationEditor().edit()) {
                    assertThat(upstream.accessControlService().addSshCredentials(
                            edit, "root", List.of(PublicKeyEntry.toString(key.getPublic()))))
                            .isInstanceOf(SshCredentialUpdateResult.Success.class);
                    edit.apply("add root test key", UserEmail.EMPTY);
                }
            }
            credential = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            int port = upstream.sshPort();
            StringBuilder hosts = new StringBuilder();
            for (var hostKey : upstream.identity().sshHostKeys().keyPairs()) {
                hosts.append(PublicKeyEntry.toString(hostKey.getPublic())).append('\n');
            }
            location = "git+ssh://root@localhost:" + port + "/bootstrap-inputs.git";
            authentication = Map.of("credentialKind", "private-key", "credential", credentialReference,
                    "knownHosts", hosts.toString());
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

    static byte[] materialBytes(BootstrapConfiguration configuration, Map<String, String> environment)
            throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (var material = OrionKeyMaterialFactory.open(configuration, environment, store, true)) {
            SshHostKeyLifecycle.open(material.sshHostKeyMaterial(), List.of());
        }
        return store.read().orElseThrow().bytes();
    }

}
