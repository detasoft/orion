package pro.deta.orion.config;

import pro.deta.orion.git.fileapi.GitCommitAuthor;

import pro.deta.orion.git.fileapi.GitCommitMetadata;

import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.acl.AccessControlAccess;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.internal.ChangeMetadata;
import java.io.ByteArrayOutputStream;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.acl.RootPasswordReset;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NativeSshCredentialPersistenceTest {
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    @TempDir
    Path root;

    @Test
    void activatesAndReloadsANewKeyWithoutChangingOtherFiles() throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.file(root, EVENTS.manager());
        NativeGitRepository repository = provider.create("acl").valueOrFailure("create repository");
        OrionVersionedConfigurationStorage storage = new NativeGitOrionConfigurationStorage(
                provider, "acl", "refs/heads/main", "users.xml");
        User alice = new User("alice", null, null, "alice@example.test",
                List.of(), List.of(), List.of());
        AccessControl primary = new AccessControl(List.of(alice), List.of(), List.of());
        ByteArrayOutputStream users = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(primary), users);
        ByteArrayOutputStream roles = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(new AccessControl()), roles);
        byte[] unchanged = roles.toByteArray();
        repository
                .files()
                .withAccess(
                        "refs/heads/main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "seed"),
                        access -> {
                            access.write("users.xml", users.toByteArray());
                            access.write("roles.xml", unchanged);
                            access.apply();
                            return null;
                        });
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage,
                pro.deta.orion.keymaterial.ConfigurationSecretCipher.unavailable(),
                pro.deta.orion.keymaterial.OrionKeyMaterial.unavailable(),
                EVENTS.manager());
        OrionAccessControlServiceImpl service = service(editor);
        try {
            service.onStart();
            assertThat(service.authenticateSshUser("alice", key.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            AccessControlAccess access =
                    service.createAccess(
                            service.configuration().revision(),
                            new ChangeMetadata(
                                    new UserEmail("alice", "alice@example.test"),
                                    "add SSH credentials for alice"));
            try {
                assertThat(access.addSshCredentials("alice", List.of(PublicKeyEntry.toString(key.getPublic()))))
                        .isInstanceOfSatisfying(SshCredentialUpdateResult.Success.class,
                                success -> assertThat(success.changed()).isTrue());
                access.apply();
            } finally {
                access.discard();
            }
            EVENTS.await();
            assertThat(service.authenticateSshUser("alice", key.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Success.class);
        } finally {
            service.onStop();
        }
        OrionAccessControlServiceImpl reopened =
                service(new OrionConfigurationEditor(
                        new NativeGitOrionConfigurationStorage(
                                NativeGitRepositoryProvider.file(root, EVENTS.manager()),
                                "acl",
                                "refs/heads/main",
                                "users.xml"),
                        pro.deta.orion.keymaterial.ConfigurationSecretCipher.unavailable(),
                        pro.deta.orion.keymaterial.OrionKeyMaterial.unavailable(),
                        EVENTS.manager()));
        try {
            reopened.onStart();
            assertThat(reopened.authenticateSshUser("alice", key.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Success.class);
            assertThat(repository.files().readBytes("refs/heads/main", "roles.xml"))
                    .containsExactly(unchanged);
        } finally {
            reopened.onStop();
        }
    }

    private static OrionAccessControlServiceImpl service(
            OrionConfigurationEditor editor
    ) {
        return new OrionAccessControlServiceImpl(
                new OrionPasswordHashingService(),
                new RootPasswordReset(false),
                OrionKeyMaterial.unavailable(),
                new SigningMaterialSet(new KeyMaterialDescriptor(new KeyMaterialAlias("signing"),
                        KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA,
                        new KeyMaterialVersion(1)), List.of()),
                new BootstrapConfiguration(),
                editor);
    }
}
