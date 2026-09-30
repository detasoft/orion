package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.*;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrionStorageConnectionsRouteTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecurityContext root = SecurityContext.createContext()
            .withUserIdentity(new InternalUserImpl("root", List.of()));

    @Test
    void createsAndInspectsSystemConnectionWithEncryptedWriteOnlyCredentials() throws Exception {
        try (KeyMaterialService material = KeyMaterialService.open(new InMemoryKeyMaterialContentStore(),
                KeyMaterialOptions.pkcs12("test-password".toCharArray()))) {
            KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias("configuration-v1"),
                    KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES,
                    new KeyMaterialVersion(1), KeyMaterialScope.cluster("test"));
            material.generateSecretKeyIfMissing(descriptor, 256);
            ConfigurationCipherCapability cipher = KeyMaterialCapabilities.open(material, List.of(descriptor))
                    .configurationCipher(descriptor);
            StorageManagementFixture.State fixture = StorageManagementFixture.open(
                    new InMemoryNativeGitRepositoryProvider(), List.of(), cipher);
            OrionStorageConnectionsRoute route = new OrionStorageConnectionsRoute(fixture.management(), mapper);
            String body = """
                    {"revision":"v1","create":true,"connection":{"name":"archive","region":"us-east-1",
                    "pathStyleAccess":true,"accessKeyId":"public-id","secretKey":"http-private-secret"}}
                    """;
            OrionHttpResponse saved = route.doPost(StorageManagementFixture.request(body, root, null));
            assertThat(saved.status()).isEqualTo(200);
            assertThat(mapper.writeValueAsString(saved.body())).contains("archive", "public-id")
                    .doesNotContain("http-private-secret", "s3-key-");
            assertThat(route.doPost(StorageManagementFixture.request(body, root, null)).status()).isEqualTo(409);
            assertThat(mapper.writeValueAsString(route.doGet(
                    StorageManagementFixture.request("", root, null)).body())).doesNotContain("http-private-secret");
        }
    }

    @Test
    void anonymousAndOrganizationIdentitiesCannotManageSystemConnections() throws Exception {
        StorageManagement management = StorageManagementFixture.create(new InMemoryNativeGitRepositoryProvider(), List.of());
        OrionStorageConnectionsRoute route = new OrionStorageConnectionsRoute(management, mapper);
        for (SecurityContext context : List.of(SecurityContext.createContext(), SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl("alice", new pro.deta.orion.schema.orion.OrganizationId("acme"),
                        () -> pro.deta.orion.schema.orion.OrionDocument.withAccessControl(
                                new pro.deta.orion.schema.acl.AccessControl()))))) {
            assertThat(route.doGet(StorageManagementFixture.request("", context, null)).status()).isEqualTo(403);
            assertThat(route.doPost(StorageManagementFixture.request("""
                    {"revision":"v1","create":true,"connection":{"name":"archive","defaultCredentials":true}}
                    """, context, null)).status()).isEqualTo(403);
        }
    }
}
