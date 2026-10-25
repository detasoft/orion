package pro.deta.orion.transport.http;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.*;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrionStorageConnectionsRouteTest {
    private final pro.deta.orion.test.util.TestEvents events =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        events.close();
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecurityContext root = SecurityContext.createContext()
            .withUserIdentity(new InternalUserImpl("root", List.of()));

    @Test
    void createsAndInspectsSystemConnectionWithEncryptedWriteOnlyCredentials() throws Exception {
        try (OrionKeyMaterial material = OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(),
                KeyMaterialOptions.pkcs12("test-password".toCharArray()))) {
            StorageManagementFixture.State fixture = StorageManagementFixture.open(events,
                    NativeGitRepositoryProvider.inMemory(events.manager()), List.of(), material);
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
        StorageManagement management =
                StorageManagementFixture.create(
                        events, NativeGitRepositoryProvider.inMemory(events.manager()), List.of());
        OrionStorageConnectionsRoute route = new OrionStorageConnectionsRoute(management, mapper);
        for (SecurityContext context : List.of(SecurityContext.createContext(), SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl("alice", new OrganizationId("acme"),
                        () -> OrionDocument.withAccessControl(
                                new pro.deta.orion.schema.acl.AccessControl()))))) {
            assertThat(route.doGet(StorageManagementFixture.request("", context, null)).status()).isEqualTo(403);
            assertThat(route.doPost(StorageManagementFixture.request("""
                    {"revision":"v1","create":true,"connection":{"name":"archive","defaultCredentials":true}}
                    """, context, null)).status()).isEqualTo(403);
        }
    }
}
