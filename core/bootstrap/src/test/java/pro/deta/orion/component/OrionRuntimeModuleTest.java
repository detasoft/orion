package pro.deta.orion.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.acl.storage.LocalAccessControlStorage;
import pro.deta.orion.acl.storage.NativeGitAccessControlStorage;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.keymaterial.KeyMaterialService;
import pro.deta.orion.keymaterial.OrionKeyMaterial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

class OrionRuntimeModuleTest {
    private static final String BRANCH = "master";
    private static final String ACL_FILE = "acl.xml";
    private static final String TEST_PASSWORD_HASH = "acl-password-hash";

    @TempDir
    private Path tempDir;

    private final XmlService xmlService = new XmlService();

    @Test
    void runtimeComponentExposesNoRawMaterialOwnerOrService() {
        assertThat(OrionComponent.class.getMethods())
                .noneMatch(method -> method.getReturnType().equals(OrionKeyMaterial.class))
                .noneMatch(method -> method.getReturnType().equals(KeyMaterialService.class));
        assertThat(OrionComponent.Builder.class.getMethods())
                .noneMatch(method -> List.of(method.getParameterTypes()).contains(OrionKeyMaterial.class))
                .noneMatch(method -> List.of(method.getParameterTypes()).contains(KeyMaterialService.class));
    }

    @Test
    void fileAclStartsFromLocalDirectory() throws Exception {
        Path aclDirectory = tempDir.resolve("acl-directory");
        Files.createDirectories(aclDirectory);
        Files.write(aclDirectory.resolve(ACL_FILE), aclBytes("file-user"));
        OrionConfiguration configuration = configurationWithAcl(aclDirectory.toUri().toString());

        AccessControlStorage storage = runtimeAccessControlStorage(configuration);

        assertInstanceOf(LocalAccessControlStorage.class, storage);
        assertStorageLoadsUser(storage, "file-user");
    }

    @Test
    void localAclSavesToLocalDirectory() {
        OrionConfiguration configuration = configurationWithAcl(tempDir.resolve("local-acl").toString());
        AccessControlStorage storage = runtimeAccessControlStorage(configuration);

        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, "native acl".getBytes(StandardCharsets.UTF_8)),
                new AccessControlSaveRequest("native acl", new UserEmail("tester", "tester@example.test")));

        AccessControlSnapshot snapshot =
                storage.load().valueOrFailure("ACL should load from local storage");
        assertEquals("native acl", new String(snapshot.files().get(ACL_FILE), StandardCharsets.UTF_8));
    }

    @Test
    void localLocatorUsesConfiguredNativeRepository() {
        OrionConfiguration configuration = configurationWithAcl("local:internal/settings");
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();

        AccessControlStorage storage = resolvedStorage(configuration, provider);
        storage.save(
                AccessControlSnapshot.singleFile(ACL_FILE, "versioned acl".getBytes(StandardCharsets.UTF_8)),
                new AccessControlSaveRequest("versioned acl", UserEmail.EMPTY));

        assertInstanceOf(NativeGitAccessControlStorage.class, storage);
        assertEquals(List.of("internal/settings"), provider.repositoryNames());
        assertEquals(
                "versioned acl",
                new String(storage.load().valueOrFailure("ACL should load").files().get(ACL_FILE),
                        StandardCharsets.UTF_8));
    }

    @Test
    void remoteGitAclIsUnsupported() {
        OrionConfiguration configuration = configurationWithAcl("ssh://git@example.test/acl.git");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeAccessControlStorage(configuration));

        assertEquals("Unsupported ACL location: ssh://git@example.test/acl.git", error.getMessage());
    }

    private AccessControlStorage runtimeAccessControlStorage(OrionConfiguration configuration) {
        return resolvedStorage(configuration, new InMemoryNativeGitRepositoryProvider());
    }

    private static AccessControlStorage resolvedStorage(
            OrionConfiguration configuration,
            InMemoryNativeGitRepositoryProvider backend) {
        ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(backend);
        ResolvedBootstrapSource resolved = provider.resolveProvisional(
                BootstrapRepositorySources.CONFIGURATION,
                configuration.getBootstrap().getAccessControl(),
                configuration.getBootstrap().getAccessControl().isCreateDefaultIfMissing());
        return new AccessControlStorageResolver(
                new BootstrapRepositorySources(List.of(resolved)),
                provider).resolve();
    }

    private byte[] aclBytes(String userId) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        xmlService.serialize(accessControlWithUser(userId), output);
        return output.toByteArray();
    }

    private AccessControl accessControlWithUser(String userId) {
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.ARGON2, TEST_PASSWORD_HASH));
        return draft.toAccessControl();
    }

    private void assertStorageLoadsUser(AccessControlStorage storage, String userId) throws Exception {
        AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load from storage");
        AccessControl accessControl =
                xmlService.deserialize(new ByteArrayInputStream(snapshot.files().get(ACL_FILE)));
        assertEquals(1, accessControl.getUsers().size());
        assertEquals(userId, accessControl.getUsers().getFirst().getId());
    }

    private OrionConfiguration configurationWithAcl(String location) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getStorage().setLocation(tempDir.resolve("repos").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation(location);
        configuration.getBootstrap().getAccessControl().setRef(BRANCH);
        configuration.getBootstrap().getAccessControl().setPath(ACL_FILE);
        return configuration;
    }
}
