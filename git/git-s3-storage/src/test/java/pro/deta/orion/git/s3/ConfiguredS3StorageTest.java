package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class ConfiguredS3StorageTest {
    @TempDir
    Path directory;
    @Test
    void retainsTheIndexOwnerThroughCredentialRotationAndSeparatesChangedLocations() throws Exception {
        try (S3GitIndexTest.Wire wire = new S3GitIndexTest.Wire();
             S3ConfigurationFixture fixture = new S3ConfigurationFixture()) {
            String endpoint = "http://127.0.0.1:" + wire.server.getAddress().getPort();
            fixture.connection(true, "archive", endpoint, "us-east-1", "old-id", "old-key", null);
            fixture.bind("repo", true, "archive", "s3://bucket/prefix");
            NativeGitRepository first = fixture.provider.create("acme/dev/repo").valueOrFailure("create");
            GitIndexAccess reader = first.index().createAccess();
            PackId pack = PackId.create();
            GitIndexAccess writer = first.index().createAccess(Optional.of(pack));
            try (AutoCloseable completion = () -> { reader.discard(); writer.discard(); }) {
                assertThat(reader.packs()).isEmpty();
                IndexedObject object = new IndexedObject(pack, new ObjectId("a".repeat(40)),
                        GitObjectType.BLOB, 1, 0, 4, Optional.empty());
                writer.addObject(object);
                fixture.connection(true, "archive", endpoint, "eu-west-1", "new-id", "new-key", "new-token");
                fixture.bind("repo", true, "archive", "s3://bucket/prefix/");
                NativeGitRepository next = fixture.provider.find("acme%2Fdev%2Frepo").valueOrFailure("find");
                assertThat(next).isSameAs(first);
                assertThat(next.index().withAccess(access -> access.packs()).isEmpty()).isTrue();
                writer.publishIndex(new PackMetadata(pack, new PackChecksum("b".repeat(40)),
                        pack.toString(), 1, 40));
                assertThat(wire.authorizations.getLast()).contains("Credential=new-id/", "/eu-west-1/s3/");
                assertThat(reader.locations(object.objectId())).containsExactly(object);
                List<IndexedObject> locations = next.index().withAccess(
                        access -> access.locations(object.objectId()));
                assertThat(locations).containsExactly(object);
                assertThat(wire.indexLists).hasValue(1);
                assertThat(wire.indexGets).hasValue(0);
                PackId laterPack = PackId.create();
                GitIndexAccess laterWriter = first.index().createAccess(Optional.of(laterPack));
                fixture.bind("repo", true, "archive", "s3://bucket/other");
                NativeGitRepository other = fixture.provider.create("acme/dev/repo").valueOrFailure("other");
                assertThat(other).isNotSameAs(first);
                IndexedObject later = new IndexedObject(laterPack, new ObjectId("c".repeat(40)),
                        GitObjectType.BLOB, 1, 0, 4, Optional.empty());
                try (AutoCloseable laterCompletion = laterWriter::discard) {
                    laterWriter.addObject(later);
                    laterWriter.publishIndex(new PackMetadata(laterPack, new PackChecksum("d".repeat(40)),
                            laterPack.toString(), 1, 40));
                }
                fixture.bind("repo", true, "archive", "s3://bucket/prefix");
                assertThat(fixture.provider.find("acme/dev/repo").valueOrFailure("return to first"))
                        .isSameAs(first);
                first.index().withAccess(access -> {
                    assertThat(access.locations(later.objectId())).containsExactly(later);
                    return null;
                });
                assertThat(reader.locations(object.objectId())).containsExactly(object);
                assertThat(other.index().withAccess(access -> access.packs()).isEmpty()).isTrue();
                fixture.provider.close();
                assertThatThrownBy(other.index()::createAccess).isInstanceOf(IOException.class);
                assertThatThrownBy(first.index()::createAccess).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void removingABindingStopsRoutingAndReaddingItReusesTheOwner() throws Exception {
        for (boolean listing : List.of(false, true)) {
            try (S3GitIndexTest.Wire wire = new S3GitIndexTest.Wire();
                 S3ConfigurationFixture fixture = new S3ConfigurationFixture()) {
                String endpoint = "http://127.0.0.1:" + wire.server.getAddress().getPort();
                fixture.connection(true, "archive", endpoint, "us-east-1", "id", "key", null);
                fixture.bind("repo", true, "archive", "s3://bucket/prefix");
                NativeGitRepository repository = fixture.provider.create("acme/dev/repo").valueOrFailure("create");
                OrionDocument bound = fixture.current.get();
                fixture.current.set(new OrionDocument(bound.system(), List.of()));
                if (listing) assertThat(fixture.provider.repositoryNames()).isEmpty();
                else assertThat(fixture.provider.exists("acme/dev/repo")).isFalse();
                assertThat(fixture.provider.find("acme/dev/repo").isFailure()).isTrue();
                fixture.current.set(bound);
                assertThat(fixture.provider.find("acme/dev/repo").valueOrFailure("readd binding"))
                        .isSameAs(repository);
            }
        }
    }

    @Test
    void changedEndpointRegionAndEncryptedCredentialsApplyOnlyToSubsequentOperations() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (S3TransportTest.Server old = new S3TransportTest.Server(entered, release);
             S3TransportTest.Server next = new S3TransportTest.Server(new CountDownLatch(0), new CountDownLatch(0));
             S3ConfigurationFixture fixture = new S3ConfigurationFixture(directory);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.connection(true, "archive", old.endpoint(), "eu-west-1", "old-id", "old-key", "old-token");
            fixture.bind("repo", true, "archive", "s3://bucket/prefix");
            Future<Boolean> inFlight = executor.submit(() -> fixture.provider.exists("acme/dev/repo"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                fixture.connection(true, "archive", next.endpoint(), "ap-south-1", "new-id", "new-key", "new-token");
                assertThat(fixture.provider.exists("acme/dev/repo")).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(inFlight.get()).isFalse();
            assertThat(old.requests.getFirst().authorization()).contains("Credential=old-id/", "/eu-west-1/s3/");
            assertThat(old.requests.getFirst().token()).isEqualTo("old-token");
            assertThat(next.requests.getFirst().authorization()).contains("Credential=new-id/", "/ap-south-1/s3/");
            assertThat(next.requests.getFirst().token()).isEqualTo("new-token");
        }
    }

    @Test
    void explicitScopeSurvivesEncryptedSecretAndAclEditsAndS3AbsenceSuppressesLocalData() throws Exception {
        try (S3TransportTest.Server server = new S3TransportTest.Server(new CountDownLatch(0), new CountDownLatch(0));
             S3ConfigurationFixture fixture = new S3ConfigurationFixture(directory)) {
            fixture.connection(true, "archive", server.endpoint(), "us-east-1", "system-id", "system-key", null);
            fixture.connection(false, "archive", server.endpoint(), "us-east-1", "organization-id", "org-key", null);
            fixture.bind("system", true, "archive", "s3://bucket/system");
            fixture.bind("organization", false, "archive", "s3://bucket/organization");
            fixture.connection(false, "archive", server.endpoint(), "eu-west-1", "organization-id", "changed-key", null);
            fixture.current.set(fixture.current.get().replaceAccessControl(new AccessControl()));
            ByteArrayOutputStream xml = new ByteArrayOutputStream();
            OrionXml.write(fixture.current.get(), xml);
            assertThat(xml.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("system-key", "changed-key");
            fixture.current.set(OrionXml.read(new ByteArrayInputStream(xml.toByteArray())));
            fixture.local.create("acme/dev/system").valueOrFailure("local data");
            fixture.local.create("local-repo").valueOrFailure("unbound data");
            assertThat(fixture.provider.exists("acme%2Fdev%2Fsystem")).isFalse();
            assertThat(fixture.provider.exists("acme/dev/organization")).isFalse();
            assertThat(server.requests.get(0).authorization()).contains("Credential=system-id/");
            assertThat(server.requests.get(1).authorization()).contains("Credential=organization-id/", "/eu-west-1/s3/");
            assertThat(fixture.provider.repositoryNames()).containsExactly("local-repo");
            assertThat(fixture.provider.find("acme/dev/system").isFailure()).isTrue();
        }
    }

    @Test
    void unrelatedConnectionCredentialsDoNotBlockUnboundLocalRepositories() throws Exception {
        try (S3ConfigurationFixture fixture = new S3ConfigurationFixture(directory)) {
            fixture.connection(true, "unavailable", "http://127.0.0.1:1", "us-east-1", "id", "key", null);
            fixture.bind("remote", true, "unavailable", "s3://bucket/prefix");
            fixture.local.create("local-repo").valueOrFailure("local data");
            ConfigurationSecrets unavailable = new ConfigurationSecrets(fixture.current::get,
                    ConfigurationCipherCapability.unavailable());
            fixture.factory.activate(fixture.current::get, unavailable, ignored -> false);
            assertThat(fixture.provider.exists("local-repo")).isTrue();
            fixture.provider.find("local-repo").valueOrFailure("local read");
            assertThatThrownBy(() -> fixture.provider.exists("acme/dev/remote"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.provider.find("acme/dev/remote").isFailure()).isTrue();
            assertThat(fixture.provider.create("acme/dev/remote").isFailure()).isTrue();
            assertThat(fixture.provider.exists("local-repo")).isTrue();
        }
    }

    @Test
    void bootstrapBindingsAreRejectedBeforeRuntimeActivation() throws Exception {
        try (S3ConfigurationFixture fixture = new S3ConfigurationFixture(directory)) {
            fixture.connection(true, "archive", "http://127.0.0.1:1", "us-east-1", "id", "key", null);
            fixture.bind("config", true, "archive", "s3://bucket/config");
            assertThatThrownBy(() -> fixture.factory.activate(fixture.current::get, fixture.secrets,
                    "acme/dev/config"::equals)).hasMessageContaining("must remain file-backed");
        }
    }
}
