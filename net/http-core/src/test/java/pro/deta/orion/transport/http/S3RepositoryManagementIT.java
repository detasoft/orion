package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.auth.*;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory;
import pro.deta.orion.git.s3.S3Transport;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.schema.orion.v2.*;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(120)
class S3RepositoryManagementIT {
    @Test
    void encryptedManagementPersistsActivatesCreatesAndReopensMetadataInMinio() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-management-" + UUID.randomUUID());
             KeyMaterialService material = KeyMaterialService.open(new InMemoryKeyMaterialContentStore(),
                     KeyMaterialOptions.pkcs12("test-password".toCharArray()));
             S3Transport transport = new S3Transport();
             NativeGitRepositoryProvider local = NativeGitRepositoryProvider.inMemory()) {
            KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias("configuration-v1"),
                    KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES,
                    new KeyMaterialVersion(1), KeyMaterialScope.cluster("test"));
            material.generateSecretKeyIfMissing(descriptor, 256);
            ConfigurationCipherCapability cipher = KeyMaterialCapabilities.open(material, List.of(descriptor))
                    .configurationCipher(descriptor);
            ConfiguredNativeGitRepositoryFactory configuredFactory = new ConfiguredNativeGitRepositoryFactory(
                    pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(), transport);
            NativeGitRepositoryProvider configured = new NativeGitRepositoryProvider(configuredFactory);
            OrionDocument.Organization organization = new OrionDocument.Organization(new OrganizationId("acme"), "",
                    List.of(), List.of(), List.of(), List.of(new OrionDocument.Team(new TeamId("team"), "",
                    List.of(), List.of(), List.of())), List.of(), List.of(), List.of(), List.of());
            StorageManagementFixture.State fixture = StorageManagementFixture.open(configured, List.of(organization), cipher);
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> fixture.desired().current().document(), cipher);
            configuredFactory.activate(() -> fixture.desired().current().document(), secrets, name -> false);
            SecurityContext actor = SecurityContext.createContext().withUserIdentity(new InternalUserImpl("root",
                    pro.deta.orion.schema.acl.ACLUtil.generateDefaultAccessControl("unused").grants()));
            StorageManagement management = fixture.management();
            assertThat(management.saveConnection(actor, Optional.empty(), "v1", true,
                    new StorageManagement.S3Input("minio", server.endpoint(), "us-east-1", true,
                            server.accessKeyId(), server.secretAccessKey().toCharArray(), null, false)))
                    .isInstanceOf(StorageManagement.Success.class);
            S3StorageBinding binding = new S3StorageBinding(new ConnectionReference(ConnectionReference.Scope.SYSTEM, "minio"),
                    URI.create("s3://" + server.bucketName() + "/management"));
            OrionAdminCreateRepositoryRoute http = new OrionAdminCreateRepositoryRoute(configured,
                    management, new com.fasterxml.jackson.databind.ObjectMapper());
            String json = "{\"name\":\"acme/team/archive\",\"connectionScope\":\"system\","
                    + "\"connection\":\"minio\",\"location\":\"" + binding.location() + "\"}";
            assertThat(http.doPost(StorageManagementFixture.request(json, actor, null)).status()).isEqualTo(201);
            assertThat(local.repositoryNames()).isEmpty();
            assertThat(fixture.desired().current().document().organizations().getFirst().teams().getFirst()
                    .repositories().getFirst().storage()).contains(binding);
            ConfiguredNativeGitRepositoryFactory reopenedFactory = new ConfiguredNativeGitRepositoryFactory(
                    pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(), transport);
            NativeGitRepositoryProvider reopened = new NativeGitRepositoryProvider(reopenedFactory);
            reopenedFactory.activate(() -> fixture.desired().current().document(), secrets, name -> false);
            NativeGitRepository repository = reopened.find("acme/team/archive").valueOrFailure("reopen");
            assertThat(repository.name()).isEqualTo("acme/team/archive");
            assertThat(repository.index().getHEAD()).isEqualTo(new Head.Symbolic(new RefId("refs/heads/main")));
            assertThat(repository.refs()).isEmpty();
            assertThat(management.createRepository(actor, "acme/team/archive", Optional.of(binding)))
                    .isEqualTo(new StorageManagement.Success<>(new StorageManagement.Created(false)));
        }
    }
}
