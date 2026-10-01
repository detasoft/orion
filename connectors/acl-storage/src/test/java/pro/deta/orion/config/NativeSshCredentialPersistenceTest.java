package pro.deta.orion.config;

import pro.deta.orion.internal.UserEmail;



import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.acl.AccessControl;
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
    void activatesAndReloadsANewKeyWithoutChangingOtherFiles() throws Exception {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(root);
        NativeGitRepository repository = provider.create("acl").valueOrFailure("create repository");
        ResolvedBootstrapSource source = new ResolvedBootstrapSource(
                "configuration", "local:acl", Optional.of("acl"), "refs/heads/main",
                "users.xml", Optional.empty(), false);
        OrionConfigurationStorage storage = new NativeGitOrionConfigurationStorage(source, provider);
        AccessControl.User alice = new AccessControl.User("alice", null, null, "alice@example.test",
                List.of(), List.of(), List.of());
        AccessControl primary = new AccessControl(List.of(alice), List.of(), List.of());
        ByteArrayOutputStream users = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(primary), users);
        ByteArrayOutputStream roles = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(new AccessControl()), roles);
        byte[] unchanged = roles.toByteArray();
        repository.files().withAccess("refs/heads/main", "seed", GitCommitAuthor.EMPTY, access -> {
            access.write("users.xml", users.toByteArray());
            access.write("roles.xml", unchanged);
            access.apply();
            return null;
        });
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        OrionDesiredState desired = new OrionDesiredState();
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage,
                new pro.deta.orion.schema.config.OrionConfiguration(),
                pro.deta.orion.keymaterial.ConfigurationCipherCapability.unavailable(),
                pro.deta.orion.keymaterial.ConfigurationMaterialCapability.unavailable(), desired);
        OrionAccessControlServiceImpl service = service(storage, editor, desired);
        try {
            service.onStart();
            assertThat(service.authenticateSshUser("alice", key.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            try (OrionConfigurationEdit edit = editor.edit()) {
                assertThat(service.addSshCredentials(edit, "alice", List.of(PublicKeyEntry.toString(key.getPublic()))))
                    .isInstanceOfSatisfying(SshCredentialUpdateResult.Success.class,
                            success -> assertThat(success.changed()).isTrue());
                edit.apply("add SSH credentials for alice", new UserEmail("alice", "alice@example.test"));
            }
            assertThat(service.authenticateSshUser("alice", key.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Success.class);
        } finally {
            service.onStop();
        }
        OrionAccessControlServiceImpl reopened = service(new NativeGitOrionConfigurationStorage(source,
                new FileNativeGitRepositoryProvider(root)), editor, desired);
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
            OrionConfigurationStorage storage, OrionConfigurationEditor editor, OrionDesiredState desired
    ) {
        return new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                ServerIdentityCapability.unavailable(),
                desired,
                editor,
                Optional.empty());
    }
}
