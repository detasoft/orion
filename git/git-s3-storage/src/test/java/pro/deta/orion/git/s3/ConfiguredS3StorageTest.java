package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class ConfiguredS3StorageTest {
    @Test
    void changedEndpointRegionAndEncryptedCredentialsApplyOnlyToSubsequentOperations() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (S3TransportTest.Server old = new S3TransportTest.Server(entered, release);
             S3TransportTest.Server next = new S3TransportTest.Server(new CountDownLatch(0), new CountDownLatch(0));
             S3ConfigurationFixture fixture = new S3ConfigurationFixture();
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
             S3ConfigurationFixture fixture = new S3ConfigurationFixture()) {
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
            fixture.local.create("acme/dev/system").valueOrFailure("local data").close();
            fixture.local.create("local-repo").valueOrFailure("unbound data").close();
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
        try (S3ConfigurationFixture fixture = new S3ConfigurationFixture()) {
            fixture.connection(true, "unavailable", "http://127.0.0.1:1", "us-east-1", "id", "key", null);
            fixture.bind("remote", true, "unavailable", "s3://bucket/prefix");
            fixture.local.create("local-repo").valueOrFailure("local data").close();
            ConfigurationSecrets unavailable = new ConfigurationSecrets(fixture.current::get,
                    ConfigurationCipherCapability.unavailable());
            fixture.provider.activate(fixture.current::get, unavailable, ignored -> false);
            assertThat(fixture.provider.exists("local-repo")).isTrue();
            fixture.provider.find("local-repo").valueOrFailure("local read").close();
            assertThatThrownBy(() -> fixture.provider.exists("acme/dev/remote"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.provider.find("acme/dev/remote").isFailure()).isTrue();
            assertThat(fixture.provider.create("acme/dev/remote").isFailure()).isTrue();
            assertThat(fixture.provider.exists("local-repo")).isTrue();
        }
    }

    @Test
    void bootstrapBindingsAreRejectedBeforeRuntimeActivation() throws Exception {
        try (S3ConfigurationFixture fixture = new S3ConfigurationFixture()) {
            fixture.connection(true, "archive", "http://127.0.0.1:1", "us-east-1", "id", "key", null);
            fixture.bind("config", true, "archive", "s3://bucket/config");
            assertThatThrownBy(() -> fixture.provider.activate(fixture.current::get, fixture.secrets,
                    "acme/dev/config"::equals)).hasMessageContaining("must remain file-backed");
        }
    }
}
