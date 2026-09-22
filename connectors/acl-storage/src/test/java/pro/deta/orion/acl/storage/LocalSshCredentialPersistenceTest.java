package pro.deta.orion.acl.storage;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.util.OrionProvider;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class LocalSshCredentialPersistenceTest {
    @TempDir
    Path root;

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void activatesAndReloadsANewKeyWithAnUnchangedReadOnlySecondaryFile() throws Exception {
        BootstrapConfigurationSourceConfig configuration = new BootstrapConfigurationSourceConfig();
        configuration.setLocation(root.toUri().toString());
        configuration.setPaths(List.of("users.xml", "roles.xml"));
        AccessControlDraft primary = new AccessControlDraft();
        AccessControlDraft.User alice = new AccessControlDraft.User();
        alice.setId("alice");
        alice.setEmail("alice@example.test");
        primary.getUsers().add(alice);
        try (OutputStream output = Files.newOutputStream(root.resolve("users.xml"))) {
            new XmlService().serialize(primary.toAccessControl(), output);
        }
        Path secondary = root.resolve("roles.xml");
        try (OutputStream output = Files.newOutputStream(secondary)) {
            new XmlService().serialize(new AccessControlDraft().toAccessControl(), output);
        }
        byte[] unchanged = Files.readAllBytes(secondary);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(secondary);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        OrionEventManager events = new OrionEventManager();
        events.onStart();
        try {
            Files.setPosixFilePermissions(secondary, PosixFilePermissions.fromString("r--r--r--"));
            assumeFalse(Files.isWritable(secondary), "requires filesystem permissions to deny writes");
            OrionAccessControlServiceImpl service = service(configuration, events);
            try {
                service.onStart();
                assertThat(service.authenticateSshUser("alice", key.getPublic().getEncoded()))
                        .isInstanceOf(AuthenticationResult.Failure.class);
                assertThat(service.addSshCredentials("alice", List.of(PublicKeyEntry.toString(key.getPublic()))))
                        .isInstanceOfSatisfying(SshCredentialUpdateResult.Success.class,
                                success -> assertThat(success.changed()).isTrue());
                assertThat(service.authenticateSshUser("alice", key.getPublic().getEncoded()))
                        .isInstanceOf(AuthenticationResult.Success.class);
            } finally {
                service.onStop();
            }
            OrionAccessControlServiceImpl reopened = service(configuration, events);
            try {
                reopened.onStart();
                assertThat(reopened.authenticateSshUser("alice", key.getPublic().getEncoded()))
                        .isInstanceOf(AuthenticationResult.Success.class);
                assertThat(Files.readAllBytes(secondary)).containsExactly(unchanged);
            } finally {
                reopened.onStop();
            }
        } finally {
            Files.setPosixFilePermissions(secondary, permissions);
            events.onStop();
        }
    }

    private static OrionAccessControlServiceImpl service(
            BootstrapConfigurationSourceConfig configuration, OrionEventManager events
    ) {
        return new OrionAccessControlServiceImpl(new LocalAccessControlStorage(configuration),
                new OrionPasswordHashingService(), new OrionProvider(() -> null, () -> events, () -> null),
                OrionRuntimeOptions.defaults(), ServerIdentityCapability.unavailable(), new OrionDesiredState());
    }
}
