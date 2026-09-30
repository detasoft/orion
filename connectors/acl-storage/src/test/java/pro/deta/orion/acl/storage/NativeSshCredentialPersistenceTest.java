package pro.deta.orion.acl.storage;

import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.Map;
import pro.deta.orion.internal.UserEmail;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NativeSshCredentialPersistenceTest {
    @TempDir
    Path root;

    @Test
    void activatesAndReloadsANewKeyWithoutChangingTheSecondaryDocument() throws Exception {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(root);
        provider.create("acl").valueOrFailure("create repository");
        ResolvedBootstrapSource source = new ResolvedBootstrapSource(
                "configuration", "local:acl", Optional.of("acl"), "refs/heads/main",
                List.of("users.xml", "roles.xml"), Optional.empty(), false);
        AccessControlStorage storage = new NativeGitAccessControlStorage(source, provider);
        AccessControlDraft primary = new AccessControlDraft();
        AccessControlDraft.User alice = new AccessControlDraft.User();
        alice.setId("alice");
        alice.setEmail("alice@example.test");
        primary.getUsers().add(alice);
        ByteArrayOutputStream users = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(primary.toAccessControl()), users);
        ByteArrayOutputStream roles = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(new AccessControlDraft().toAccessControl()), roles);
        byte[] unchanged = roles.toByteArray();
        storage.save(new AccessControlSnapshot(Map.of("users.xml", users.toByteArray(),
                        "roles.xml", unchanged), Optional.empty()),
                "seed", UserEmail.EMPTY);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        OrionAccessControlServiceImpl service = service(storage);
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
        OrionAccessControlServiceImpl reopened = service(new NativeGitAccessControlStorage(source,
                new FileNativeGitRepositoryProvider(root)));
        try {
            reopened.onStart();
            assertThat(reopened.authenticateSshUser("alice", key.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Success.class);
            assertThat(storage.load().valueOrFailure("reloaded ACL").files().get("roles.xml"))
                    .containsExactly(unchanged);
        } finally {
            reopened.onStop();
        }
    }

    private static OrionAccessControlServiceImpl service(
            AccessControlStorage storage
    ) {
        return new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(), ServerIdentityCapability.unavailable(), new OrionDesiredState(),
                new pro.deta.orion.schema.config.OrionConfiguration(),
                pro.deta.orion.keymaterial.ConfigurationCipherCapability.unavailable(),
                pro.deta.orion.keymaterial.ConfigurationMaterialCapability.unavailable(),
                Optional.empty());
    }
}
